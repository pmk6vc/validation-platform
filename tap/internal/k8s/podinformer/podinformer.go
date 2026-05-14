// Package podinformer exposes Index, a goroutine-safe cgroup_id → *v1.Pod
// map maintained by a K8s shared informer scoped to pods on the local node.
//
// Usage:
//
//	idx := podinformer.NewIndex(client, "node-name", podinformer.NewFSCgroupResolver("/sys/fs/cgroup"))
//	go idx.Run(ctx)               // blocks until ctx done
//	pod, ok := idx.Lookup(cgID)   // safe from any goroutine
//
// The informer filters server-side via spec.nodeName=<node> so we only
// receive watch events for pods on this node. RBAC required: pods
// get/list/watch (cluster-scoped because the watch is field-filtered, not
// namespace-scoped).
package podinformer

import (
	"context"
	"fmt"
	"log"
	"sync"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/fields"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/informers"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/tools/cache"
)

// Index is the cgroup_id → *v1.Pod lookup table. Populated by an informer
// goroutine; queried by the BPF capture loop.
type Index struct {
	client   kubernetes.Interface
	nodeName string
	resolver CgroupResolver

	mu             sync.RWMutex
	cgroupToPod    map[uint64]*corev1.Pod
	podUIDToCgIDs  map[types.UID][]uint64 // for clean delete on pod removal
	synced         bool
}

// NewIndex constructs an Index. Does NOT start the informer — call Run.
func NewIndex(client kubernetes.Interface, nodeName string, resolver CgroupResolver) *Index {
	return &Index{
		client:        client,
		nodeName:      nodeName,
		resolver:      resolver,
		cgroupToPod:   make(map[uint64]*corev1.Pod),
		podUIDToCgIDs: make(map[types.UID][]uint64),
	}
}

// Lookup returns the pod for the given container cgroup_id, or (nil,
// false) if unknown. Safe to call from any goroutine.
func (i *Index) Lookup(cgroupID uint64) (*corev1.Pod, bool) {
	i.mu.RLock()
	defer i.mu.RUnlock()
	p, ok := i.cgroupToPod[cgroupID]
	return p, ok
}

// HasSynced reports whether the initial pod-list watch has completed. The
// capture loop can keep running before this is true — Lookup will just
// miss for known pods until the informer catches up.
func (i *Index) HasSynced() bool {
	i.mu.RLock()
	defer i.mu.RUnlock()
	return i.synced
}

// Run starts the informer and blocks until ctx is cancelled. Pod events
// drive Add/Update/Delete handlers that maintain the index. The resync
// period is set to 5 minutes — informers re-list on this cadence as a
// safety net against missed watch events.
func (i *Index) Run(ctx context.Context) {
	factory := informers.NewSharedInformerFactoryWithOptions(
		i.client,
		5*time.Minute,
		informers.WithTweakListOptions(func(opts *metav1.ListOptions) {
			// Server-side filter: only pods scheduled on this node. The
			// informer watches all namespaces but the kube-apiserver
			// drops other-node events before they reach us.
			opts.FieldSelector = fields.OneTermEqualSelector("spec.nodeName", i.nodeName).String()
		}),
	)
	podInformer := factory.Core().V1().Pods().Informer()
	_, err := podInformer.AddEventHandler(cache.ResourceEventHandlerFuncs{
		AddFunc:    i.onAdd,
		UpdateFunc: i.onUpdate,
		DeleteFunc: i.onDelete,
	})
	if err != nil {
		log.Printf("podinformer: AddEventHandler failed: %v", err)
		return
	}

	factory.Start(ctx.Done())
	if !cache.WaitForCacheSync(ctx.Done(), podInformer.HasSynced) {
		log.Printf("podinformer: initial cache sync did not complete (ctx cancelled?)")
		return
	}
	// Read map length while still holding the lock; informer event
	// handlers (onAdd / onUpdate / onDelete) write under the same mutex
	// from other goroutines, and an unlocked len() races with their
	// map mutations — caught by `go test -race` in CI.
	i.mu.Lock()
	i.synced = true
	indexed := len(i.cgroupToPod)
	i.mu.Unlock()
	log.Printf("podinformer: initial sync complete, %d cgroup ids indexed", indexed)

	<-ctx.Done()
}

// onAdd is the informer event handler for a new pod. Resolves all
// container cgroup IDs for the pod and inserts them into the index.
// Tolerates pending pods (no containers yet) — they'll resolve when the
// kubelet reports container status in a later Update.
func (i *Index) onAdd(obj interface{}) {
	pod, ok := obj.(*corev1.Pod)
	if !ok {
		return
	}
	i.resolveAndInsert(pod)
}

// onUpdate refreshes the index for the updated pod. Container restarts,
// new sidecars added by mutating admission, and Pending→Running
// transitions all surface here. We re-resolve fully (removing old
// entries, inserting new) to handle these uniformly.
func (i *Index) onUpdate(oldObj, newObj interface{}) {
	pod, ok := newObj.(*corev1.Pod)
	if !ok {
		return
	}
	i.removeByUID(pod.UID)
	i.resolveAndInsert(pod)
}

// onDelete removes every cgroup_id for the deleted pod. The pod's cgroup
// directories are gone by this point so the IDs would never resolve again
// anyway; this is an eager cleanup to keep the map small.
func (i *Index) onDelete(obj interface{}) {
	// On final deletion the informer may pass cache.DeletedFinalStateUnknown.
	pod, ok := obj.(*corev1.Pod)
	if !ok {
		tomb, ok := obj.(cache.DeletedFinalStateUnknown)
		if !ok {
			return
		}
		pod, ok = tomb.Obj.(*corev1.Pod)
		if !ok {
			return
		}
	}
	i.removeByUID(pod.UID)
}

// resolveAndInsert calls the resolver and adds every (cgroup_id, pod)
// entry it returns. Idempotent — safe to call repeatedly with the same
// pod (e.g. on Update); each call replaces the previous entries.
func (i *Index) resolveAndInsert(pod *corev1.Pod) {
	cgs, err := i.resolver.Resolve(pod)
	if err != nil {
		// Resolution can fail transiently (pod not yet scheduled, cgroup
		// directory race during pod startup). Log at debug-equivalent
		// volume and move on.
		log.Printf("podinformer: resolve %s/%s: %v", pod.Namespace, pod.Name, err)
		return
	}
	if len(cgs) == 0 {
		return
	}
	i.mu.Lock()
	defer i.mu.Unlock()
	ids := make([]uint64, 0, len(cgs))
	for _, c := range cgs {
		i.cgroupToPod[c.CgroupID] = pod
		ids = append(ids, c.CgroupID)
	}
	i.podUIDToCgIDs[pod.UID] = ids
}

// removeByUID drops every cgroup_id known for the given pod UID. Called
// from onDelete and from onUpdate (before re-inserting).
func (i *Index) removeByUID(uid types.UID) {
	i.mu.Lock()
	defer i.mu.Unlock()
	ids := i.podUIDToCgIDs[uid]
	for _, id := range ids {
		delete(i.cgroupToPod, id)
	}
	delete(i.podUIDToCgIDs, uid)
}

// Size returns the current number of indexed cgroup_ids. Diagnostic only.
func (i *Index) Size() int {
	i.mu.RLock()
	defer i.mu.RUnlock()
	return len(i.cgroupToPod)
}

// CheckInvariant verifies internal consistency between the forward
// (cgroup_id → pod) and reverse (pod_uid → [cgroup_ids]) maps. Returns
// nil if both maps agree on every entry, or a descriptive error if they
// don't. Cheap (O(N) over the index); intended to be called periodically
// from a sweep goroutine (PR3) or from tests.
//
// The two maps SHOULD always agree because every write goes through
// resolveAndInsert or removeByUID, both of which update both maps under
// the same lock. A violation here means a bug somewhere in our code, not
// a runtime race or external corruption. Treat as "loud failure" rather
// than "auto-heal."
func (i *Index) CheckInvariant() error {
	i.mu.RLock()
	defer i.mu.RUnlock()

	// Every reverse-map entry must point at a forward-map entry for the
	// same pod UID. No two reverse entries may claim the same cgroup_id.
	claimed := make(map[uint64]types.UID, len(i.cgroupToPod))
	for uid, ids := range i.podUIDToCgIDs {
		for _, id := range ids {
			if prev, dup := claimed[id]; dup {
				return invariantErrorf(
					"cgroup %d claimed by both pods %s and %s in reverse map",
					id, prev, uid)
			}
			claimed[id] = uid
			pod, ok := i.cgroupToPod[id]
			if !ok {
				return invariantErrorf(
					"cgroup %d in reverse map for pod %s but missing from forward map",
					id, uid)
			}
			if pod.UID != uid {
				return invariantErrorf(
					"cgroup %d → pod %s in forward map but reverse map claims pod %s",
					id, pod.UID, uid)
			}
		}
	}
	// Every forward-map entry must be claimed by exactly one reverse entry.
	for id, pod := range i.cgroupToPod {
		if _, ok := claimed[id]; !ok {
			return invariantErrorf(
				"cgroup %d → pod %s in forward map but has no reverse entry",
				id, pod.UID)
		}
	}
	return nil
}

func invariantErrorf(format string, args ...interface{}) error {
	return fmt.Errorf("podinformer index invariant violated: "+format, args...)
}
