package podinformer

import (
	"os"
	"path/filepath"
	"testing"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
)

func TestPodCgroupSlicePath(t *testing.T) {
	cases := []struct {
		name     string
		uid      types.UID
		qos      corev1.PodQOSClass
		expected string
	}{
		{
			name:     "guaranteed",
			uid:      "abc12345-6789-4abc-def0-123456789abc",
			qos:      corev1.PodQOSGuaranteed,
			expected: "kubepods.slice/kubepods-podabc12345_6789_4abc_def0_123456789abc.slice",
		},
		{
			name:     "burstable",
			uid:      "abc12345-6789-4abc-def0-123456789abc",
			qos:      corev1.PodQOSBurstable,
			expected: "kubepods.slice/kubepods-burstable.slice/kubepods-burstable-podabc12345_6789_4abc_def0_123456789abc.slice",
		},
		{
			name:     "besteffort",
			uid:      "abc12345-6789-4abc-def0-123456789abc",
			qos:      corev1.PodQOSBestEffort,
			expected: "kubepods.slice/kubepods-besteffort.slice/kubepods-besteffort-podabc12345_6789_4abc_def0_123456789abc.slice",
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			pod := &corev1.Pod{
				ObjectMeta: metav1.ObjectMeta{UID: c.uid},
				Status:     corev1.PodStatus{QOSClass: c.qos},
			}
			got := podCgroupSlicePath(pod)
			if got != c.expected {
				t.Errorf("got %q, want %q", got, c.expected)
			}
		})
	}
}

func TestPodCgroupSlicePathEmptyOnMissingFields(t *testing.T) {
	cases := []struct {
		name string
		pod  *corev1.Pod
	}{
		{"nil", nil},
		{"no UID", &corev1.Pod{Status: corev1.PodStatus{QOSClass: corev1.PodQOSBurstable}}},
		{"no QoS class", &corev1.Pod{ObjectMeta: metav1.ObjectMeta{UID: "abc"}}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := podCgroupSlicePath(c.pod); got != "" {
				t.Errorf("got %q, want empty", got)
			}
		})
	}
}

func TestIsContainerScope(t *testing.T) {
	cases := map[string]bool{
		"cri-containerd-abc123.scope": true,
		"docker-abc123.scope":         true,
		"crio-abc123.scope":           true,
		"memory.max":                  false,
		"cpu.weight":                  false,
		"cgroup.procs":                false,
		"some-other.scope":            false, // unknown runtime
		"cri-containerd-abc123":       false, // missing .scope suffix
	}
	for in, want := range cases {
		if got := isContainerScope(in); got != want {
			t.Errorf("isContainerScope(%q) = %v, want %v", in, got, want)
		}
	}
}

func TestContainerNameFromScope_MatchesPodStatus(t *testing.T) {
	id := "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"
	pod := &corev1.Pod{
		Status: corev1.PodStatus{
			ContainerStatuses: []corev1.ContainerStatus{
				{Name: "api-gateway", ContainerID: "containerd://" + id},
			},
		},
	}
	scope := "cri-containerd-" + id + ".scope"
	if got := containerNameFromScope(scope, pod); got != "api-gateway" {
		t.Errorf("got %q, want %q", got, "api-gateway")
	}
}

func TestContainerNameFromScope_FallbackOnNoMatch(t *testing.T) {
	pod := &corev1.Pod{} // no container statuses
	scope := "cri-containerd-abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890.scope"
	got := containerNameFromScope(scope, pod)
	if got != "container-abcdef123456" {
		t.Errorf("got %q, want %q", got, "container-abcdef123456")
	}
}

// TestResolve_Walks tests the filesystem-walking path of the resolver
// against a tmpdir mocking /sys/fs/cgroup. We can't validate the actual
// cgroup IDs (those are kernel inodes) — only that the right directories
// get stat'd and the inode numbers come through to the caller.
func TestResolve_Walks(t *testing.T) {
	root := t.TempDir()
	uid := "abc12345-6789-4abc-def0-123456789abc"
	slice := filepath.Join(root,
		"kubepods.slice",
		"kubepods-burstable.slice",
		"kubepods-burstable-podabc12345_6789_4abc_def0_123456789abc.slice",
	)
	if err := os.MkdirAll(filepath.Join(slice, "cri-containerd-aaa.scope"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(slice, "cri-containerd-bbb.scope"), 0o755); err != nil {
		t.Fatal(err)
	}
	// A non-scope entry that should be ignored.
	if err := os.WriteFile(filepath.Join(slice, "memory.max"), []byte("max\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	pod := &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{UID: types.UID(uid)},
		Status:     corev1.PodStatus{QOSClass: corev1.PodQOSBurstable},
	}
	r := NewFSCgroupResolver(root)
	got, err := r.Resolve(pod)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 2 {
		t.Fatalf("got %d container cgroups, want 2", len(got))
	}
	// IDs should be non-zero (inode numbers from the tmpdir).
	for _, c := range got {
		if c.CgroupID == 0 {
			t.Errorf("expected non-zero cgroup_id, got 0")
		}
	}
}

// TestResolve_PendingPodIsNotAnError exercises the case where the pod's
// cgroup slice directory doesn't exist yet (kubelet hasn't created it).
// We should get an empty result, no error.
func TestResolve_PendingPodIsNotAnError(t *testing.T) {
	root := t.TempDir()
	pod := &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{UID: "no-such-pod"},
		Status:     corev1.PodStatus{QOSClass: corev1.PodQOSBurstable},
	}
	r := NewFSCgroupResolver(root)
	got, err := r.Resolve(pod)
	if err != nil {
		t.Errorf("expected nil error for missing slice, got %v", err)
	}
	if len(got) != 0 {
		t.Errorf("expected empty result, got %d entries", len(got))
	}
}
