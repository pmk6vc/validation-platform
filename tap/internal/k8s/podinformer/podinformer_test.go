package podinformer

import (
	"context"
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
