package podinformer

import (
	"context"
	"fmt"
	"sync"
	"testing"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/kubernetes/fake"
)

// fakeResolver is a CgroupResolver double for tests — returns canned cgroup
// IDs based on the pod's UID so tests can control the (uid → cgroup_id)
// mapping without touching the real filesystem.
type fakeResolver struct {
	mu  sync.Mutex
	uid map[types.UID][]ContainerCgroup
}

func newFakeResolver() *fakeResolver {
	return &fakeResolver{uid: make(map[types.UID][]ContainerCgroup)}
}

func (f *fakeResolver) set(uid types.UID, cgs []ContainerCgroup) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.uid[uid] = cgs
}

func (f *fakeResolver) Resolve(pod *corev1.Pod) ([]ContainerCgroup, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.uid[pod.UID], nil
}

func newTestPod(uid, name, node string, cgs []ContainerCgroup) *corev1.Pod {
	return &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{
			UID:       types.UID(uid),
			Name:      name,
			Namespace: "default",
		},
		Spec: corev1.PodSpec{NodeName: node},
		Status: corev1.PodStatus{
			QOSClass: corev1.PodQOSBurstable,
		},
	}
}

// waitFor polls cond every 10ms for up to 2s, returns true if it ever
// returned true. Used to wait for informer event handlers to run without
// hardcoding sleep durations.
func waitFor(t *testing.T, cond func() bool) bool {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return true
		}
		time.Sleep(10 * time.Millisecond)
	}
	return cond()
}

func TestIndex_PodAddPopulatesLookup(t *testing.T) {
	client := fake.NewSimpleClientset()
	res := newFakeResolver()
	pod := newTestPod("pod-uid-1", "api-gateway", "node-a", nil)
	res.set(pod.UID, []ContainerCgroup{
		{CgroupID: 13312, ContainerName: "api-gateway"},
	})

	idx := NewIndex(client, "node-a", res)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() { idx.Run(ctx); close(done) }()

	_, err := client.CoreV1().Pods("default").Create(ctx, pod, metav1.CreateOptions{})
	if err != nil {
		t.Fatal(err)
	}

	if !waitFor(t, func() bool {
		_, ok := idx.Lookup(13312)
		return ok
	}) {
		t.Fatalf("informer did not index cgroup 13312 within timeout")
	}
	got, ok := idx.Lookup(13312)
	if !ok {
		t.Fatal("Lookup miss after add")
	}
	if got.Name != "api-gateway" {
		t.Errorf("got pod %q, want api-gateway", got.Name)
	}

	cancel()
	<-done
}

func TestIndex_PodDeleteEvictsLookup(t *testing.T) {
	client := fake.NewSimpleClientset()
	res := newFakeResolver()
	pod := newTestPod("pod-uid-2", "order-service", "node-a", nil)
	res.set(pod.UID, []ContainerCgroup{
		{CgroupID: 13170, ContainerName: "order-service"},
	})

	idx := NewIndex(client, "node-a", res)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() { idx.Run(ctx); close(done) }()

	_, err := client.CoreV1().Pods("default").Create(ctx, pod, metav1.CreateOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if !waitFor(t, func() bool { _, ok := idx.Lookup(13170); return ok }) {
		t.Fatalf("add never propagated")
	}

	err = client.CoreV1().Pods("default").Delete(ctx, pod.Name, metav1.DeleteOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if !waitFor(t, func() bool { _, ok := idx.Lookup(13170); return !ok }) {
		t.Fatalf("delete never propagated")
	}

	cancel()
	<-done
}

func TestIndex_NodeFilterExcludesOtherNodes(t *testing.T) {
	// Two pods on different nodes; informer scoped to node-a should only
	// see the one on node-a.
	client := fake.NewSimpleClientset()
	res := newFakeResolver()
	podA := newTestPod("pod-uid-a", "api-gateway", "node-a", nil)
	podB := newTestPod("pod-uid-b", "api-gateway", "node-b", nil)
	res.set(podA.UID, []ContainerCgroup{{CgroupID: 1001}})
	res.set(podB.UID, []ContainerCgroup{{CgroupID: 1002}})

	idx := NewIndex(client, "node-a", res)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() { idx.Run(ctx); close(done) }()

	_, _ = client.CoreV1().Pods("default").Create(ctx, podA, metav1.CreateOptions{})
	_, _ = client.CoreV1().Pods("default").Create(ctx, podB, metav1.CreateOptions{})

	if !waitFor(t, func() bool { _, ok := idx.Lookup(1001); return ok }) {
		t.Fatalf("expected node-a pod indexed")
	}
	// Wait a bit longer to give the other-node pod a chance to wrongly
	// land in the index — it shouldn't.
	time.Sleep(200 * time.Millisecond)
	if _, ok := idx.Lookup(1002); ok {
		t.Errorf("node-b pod leaked into node-a index")
	}

	cancel()
	<-done
}

func TestIndex_MultiContainerPod(t *testing.T) {
	// A pod with multiple containers (e.g. sidecar) should have each
	// container's cgroup_id mapped back to the same pod.
	client := fake.NewSimpleClientset()
	res := newFakeResolver()
	pod := newTestPod("multi-uid", "app-with-sidecar", "node-a", nil)
	res.set(pod.UID, []ContainerCgroup{
		{CgroupID: 2001, ContainerName: "app"},
		{CgroupID: 2002, ContainerName: "sidecar"},
		{CgroupID: 2003, ContainerName: "init"},
	})

	idx := NewIndex(client, "node-a", res)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() { idx.Run(ctx); close(done) }()

	_, _ = client.CoreV1().Pods("default").Create(ctx, pod, metav1.CreateOptions{})

	if !waitFor(t, func() bool { return idx.Size() >= 3 }) {
		t.Fatalf("expected 3 cgroup ids indexed, got %d", idx.Size())
	}
	for _, cgid := range []uint64{2001, 2002, 2003} {
		got, ok := idx.Lookup(cgid)
		if !ok {
			t.Errorf("cgroup %d not indexed", cgid)
			continue
		}
		if got.UID != "multi-uid" {
			t.Errorf("cgroup %d → wrong pod %s", cgid, got.UID)
		}
	}

	cancel()
	<-done
}

func TestIndex_LookupMissReturnsFalse(t *testing.T) {
	client := fake.NewSimpleClientset()
	res := newFakeResolver()
	idx := NewIndex(client, "node-a", res)
	if got, ok := idx.Lookup(99999); ok {
		t.Errorf("expected miss, got pod %v", got)
	}
}

func TestIndex_HasSyncedTransitionsAfterRun(t *testing.T) {
	client := fake.NewSimpleClientset()
	res := newFakeResolver()
	idx := NewIndex(client, "node-a", res)
	if idx.HasSynced() {
		t.Fatal("HasSynced should be false before Run")
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() { idx.Run(ctx); close(done) }()

	if !waitFor(t, idx.HasSynced) {
		t.Fatalf("HasSynced never transitioned to true")
	}
	cancel()
	<-done
}

// TestIndex_ContainerRestart simulates the most common real-world drift
// scenario: a container restarts under the same pod UID, getting a new
// cgroup_id from the kernel. The resolver returns the new cgroup_id on
// the second Resolve call (simulating what fsCgroupResolver would observe
// after the kubelet replaces the container's scope directory). After the
// Update event propagates, the OLD cgroup_id must be evicted and the NEW
// one inserted — both forward and reverse maps must agree.
func TestIndex_ContainerRestart(t *testing.T) {
	client := fake.NewSimpleClientset()
	res := newFakeResolver()
	pod := newTestPod("restart-uid", "api-gateway", "node-a", nil)
	const oldCgID, newCgID uint64 = 13000, 14000

	// First Resolve returns the original cgroup_id.
	res.set(pod.UID, []ContainerCgroup{{CgroupID: oldCgID, ContainerName: "api-gateway"}})

	idx := NewIndex(client, "node-a", res)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() { idx.Run(ctx); close(done) }()

	_, _ = client.CoreV1().Pods("default").Create(ctx, pod, metav1.CreateOptions{})
	if !waitFor(t, func() bool { _, ok := idx.Lookup(oldCgID); return ok }) {
		t.Fatalf("initial add never propagated")
	}
	if err := idx.CheckInvariant(); err != nil {
		t.Fatalf("invariant violated after initial add: %v", err)
	}

	// Kubelet replaces the container — same pod UID, new cgroup_id.
	res.set(pod.UID, []ContainerCgroup{{CgroupID: newCgID, ContainerName: "api-gateway"}})
	// Trigger an Update by changing pod status; the informer fires
	// onUpdate which calls removeByUID then resolveAndInsert.
	pod.Status.ContainerStatuses = []corev1.ContainerStatus{{
		Name:        "api-gateway",
		RestartCount: 1,
		ContainerID: "containerd://newcontainerid",
	}}
	_, err := client.CoreV1().Pods("default").Update(ctx, pod, metav1.UpdateOptions{})
	if err != nil {
		t.Fatal(err)
	}

	// New cgroup_id should now resolve.
	if !waitFor(t, func() bool { _, ok := idx.Lookup(newCgID); return ok }) {
		t.Fatalf("restart never propagated: new cgroup_id %d not in index", newCgID)
	}
	// Old cgroup_id must be evicted — otherwise a stale lookup could
	// misattribute a (recycled) cgroup_id to the wrong container in
	// a future scenario.
	if _, ok := idx.Lookup(oldCgID); ok {
		t.Errorf("old cgroup_id %d still in index after restart", oldCgID)
	}
	if err := idx.CheckInvariant(); err != nil {
		t.Errorf("invariant violated after restart: %v", err)
	}

	cancel()
	<-done
}

// TestIndex_ConcurrentLookupAndUpdate exercises the RWMutex contract.
// Spawns many goroutines doing Lookup concurrently while pod Add/Update/
// Delete events drive the informer. The race detector (run with
// `go test -race`) catches any unsafe map access; CheckInvariant catches
// any forward/reverse drift.
//
// We don't assert specific Lookup results — the answers race with the
// updates and are nondeterministic. We assert only:
//  1. No panic, no data race (covered by -race).
//  2. After all churn settles, the invariant holds.
func TestIndex_ConcurrentLookupAndUpdate(t *testing.T) {
	client := fake.NewSimpleClientset()
	res := newFakeResolver()
	idx := NewIndex(client, "node-a", res)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() { idx.Run(ctx); close(done) }()

	// 10 pods, each with 3 containers, churned through 5 rounds of
	// restart-and-update.
	const nPods, nContainers, nRounds = 10, 3, 5
	pods := make([]*corev1.Pod, nPods)
	for i := 0; i < nPods; i++ {
		uid := types.UID(fmt.Sprintf("conc-uid-%d", i))
		cgs := make([]ContainerCgroup, nContainers)
		for j := 0; j < nContainers; j++ {
			cgs[j] = ContainerCgroup{CgroupID: uint64(10000 + i*100 + j)}
		}
		res.set(uid, cgs)
		pods[i] = newTestPod(string(uid), fmt.Sprintf("pod-%d", i), "node-a", nil)
		_, _ = client.CoreV1().Pods("default").Create(ctx, pods[i], metav1.CreateOptions{})
	}
	// Wait for all 30 cgroup_ids to land.
	if !waitFor(t, func() bool { return idx.Size() >= nPods*nContainers }) {
		t.Fatalf("initial population never reached %d entries; got %d", nPods*nContainers, idx.Size())
	}

	// Concurrent Lookup goroutines hammering the index.
	stop := make(chan struct{})
	var readers sync.WaitGroup
	for r := 0; r < 8; r++ {
		readers.Add(1)
		go func() {
			defer readers.Done()
			for {
				select {
				case <-stop:
					return
				default:
				}
				for cgid := uint64(10000); cgid < 11000; cgid++ {
					_, _ = idx.Lookup(cgid)
				}
			}
		}()
	}

	// Update churn — simulate restarts by changing the resolver's mapping
	// for each pod and pushing an Update event.
	for r := 0; r < nRounds; r++ {
		for i := 0; i < nPods; i++ {
			cgs := make([]ContainerCgroup, nContainers)
			for j := 0; j < nContainers; j++ {
				cgs[j] = ContainerCgroup{CgroupID: uint64(10000 + i*100 + j + (r+1)*1000)}
			}
			res.set(pods[i].UID, cgs)
			pods[i].Status.ContainerStatuses = []corev1.ContainerStatus{
				{Name: "main", RestartCount: int32(r + 1)},
			}
			_, _ = client.CoreV1().Pods("default").Update(ctx, pods[i], metav1.UpdateOptions{})
		}
		time.Sleep(20 * time.Millisecond) // let events drain
	}

	close(stop)
	readers.Wait()

	// Final invariant check — forward and reverse maps must agree.
	if err := idx.CheckInvariant(); err != nil {
		t.Errorf("invariant violated after concurrent churn: %v", err)
	}

	cancel()
	<-done
}

// TestIndex_InvariantCheckDetectsForcedDrift directly exercises the
// invariant checker by inserting a deliberate inconsistency via the
// internal maps. Verifies that drift is detected rather than masked.
//
// Uses internal field access — this test lives in the same package so
// it can manipulate the maps directly. In a future PR, if we ever
// expose a "force resync" path, this test ensures CheckInvariant would
// catch the drift before resync papers over it.
func TestIndex_InvariantCheckDetectsForcedDrift(t *testing.T) {
	client := fake.NewSimpleClientset()
	res := newFakeResolver()
	idx := NewIndex(client, "node-a", res)

	pod := newTestPod("drift-uid", "test", "node-a", nil)

	// Insert a consistent baseline directly into the maps.
	idx.mu.Lock()
	idx.cgroupToPod[5000] = pod
	idx.podUIDToCgIDs[pod.UID] = []uint64{5000}
	idx.mu.Unlock()
	if err := idx.CheckInvariant(); err != nil {
		t.Fatalf("baseline should be valid: %v", err)
	}

	t.Run("forward without reverse", func(t *testing.T) {
		idx.mu.Lock()
		idx.cgroupToPod[5001] = pod // not in podUIDToCgIDs
		idx.mu.Unlock()
		if err := idx.CheckInvariant(); err == nil {
			t.Errorf("expected invariant violation, got nil")
		}
		// cleanup
		idx.mu.Lock()
		delete(idx.cgroupToPod, 5001)
		idx.mu.Unlock()
	})

	t.Run("reverse without forward", func(t *testing.T) {
		idx.mu.Lock()
		idx.podUIDToCgIDs[pod.UID] = append(idx.podUIDToCgIDs[pod.UID], 5002)
		idx.mu.Unlock()
		if err := idx.CheckInvariant(); err == nil {
			t.Errorf("expected invariant violation, got nil")
		}
		// cleanup
		idx.mu.Lock()
		idx.podUIDToCgIDs[pod.UID] = []uint64{5000}
		idx.mu.Unlock()
	})

	t.Run("reverse claims wrong pod", func(t *testing.T) {
		otherPod := newTestPod("other-uid", "other", "node-a", nil)
		idx.mu.Lock()
		idx.cgroupToPod[5000] = otherPod // pretend forward got rewritten
		idx.mu.Unlock()
		if err := idx.CheckInvariant(); err == nil {
			t.Errorf("expected invariant violation (forward and reverse disagree on pod), got nil")
		}
		// cleanup
		idx.mu.Lock()
		idx.cgroupToPod[5000] = pod
		idx.mu.Unlock()
	})

	t.Run("duplicate cgroup in reverse", func(t *testing.T) {
		otherPod := newTestPod("dup-uid", "dup", "node-a", nil)
		idx.mu.Lock()
		idx.cgroupToPod[5000] = pod // baseline
		idx.podUIDToCgIDs[otherPod.UID] = []uint64{5000} // also claims 5000
		idx.mu.Unlock()
		if err := idx.CheckInvariant(); err == nil {
			t.Errorf("expected invariant violation (two pods claim same cgroup), got nil")
		}
		// cleanup
		idx.mu.Lock()
		delete(idx.podUIDToCgIDs, otherPod.UID)
		idx.mu.Unlock()
	})

	// Final state should be clean.
	if err := idx.CheckInvariant(); err != nil {
		t.Errorf("invariant violated after cleanup: %v", err)
	}
}
