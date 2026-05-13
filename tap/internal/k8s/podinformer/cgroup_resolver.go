// Package podinformer watches K8s pods on the local node and maintains a
// cgroup_id → *v1.Pod index for the BPF event-attribution path.
//
// This file (cgroup_resolver.go) handles the second half of the problem:
// given a pod's metadata, figure out which kernel cgroup IDs correspond to
// its containers. The BPF program's `bpf_get_current_cgroup_id()` helper
// returns the *container's* cgroup ID (the deepest leaf in the cgroup
// hierarchy), so a single pod typically produces N entries in the index —
// one per container, including init containers and sidecars.
package podinformer

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"syscall"

	corev1 "k8s.io/api/core/v1"
)

// CgroupResolver maps a pod's metadata to the set of (container_cgroup_id,
// container_name) pairs for its containers. Implementations may consult the
// real filesystem (`fsCgroupResolver`) or return canned data (test doubles).
type CgroupResolver interface {
	Resolve(pod *corev1.Pod) ([]ContainerCgroup, error)
}

// ContainerCgroup is the (id, name) tuple produced per running container.
// The cgroup_id matches what `bpf_get_current_cgroup_id()` reports for a
// task running inside that container.
type ContainerCgroup struct {
	CgroupID      uint64
	ContainerName string
}

// fsCgroupResolver looks up cgroup IDs by stat()'ing kubepods.slice
// directories on the host filesystem. The DaemonSet mounts /sys/fs/cgroup
// from the host so this resolver can see them. Tests can substitute a
// different root via NewFSCgroupResolver to avoid touching the real fs.
type fsCgroupResolver struct {
	root string // cgroup v2 root, default "/sys/fs/cgroup"
}

// NewFSCgroupResolver builds a resolver against the given cgroup v2 root.
// Pass "/sys/fs/cgroup" in production; tests use a tmpdir.
func NewFSCgroupResolver(root string) CgroupResolver {
	return &fsCgroupResolver{root: root}
}

// Resolve walks the pod's cgroup slice directory and stat()s every
// `*.scope` entry (one per container) to learn its inode number, which is
// what the kernel reports as the cgroup ID. Returns an empty slice (not an
// error) if the slice directory doesn't exist yet — that happens when a
// pod is freshly scheduled but its containers haven't been started by the
// kubelet. Caller retries on the next pod update.
func (r *fsCgroupResolver) Resolve(pod *corev1.Pod) ([]ContainerCgroup, error) {
	slice := podCgroupSlicePath(pod)
	if slice == "" {
		return nil, fmt.Errorf("unsupported pod state (no UID or QoS class)")
	}
	dir := filepath.Join(r.root, slice)
	entries, err := os.ReadDir(dir)
	if err != nil {
		if os.IsNotExist(err) {
			// Pod scheduled but no containers yet. Not an error.
			return nil, nil
		}
		return nil, fmt.Errorf("reading %s: %w", dir, err)
	}
	var out []ContainerCgroup
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		name := e.Name()
		if !isContainerScope(name) {
			continue
		}
		info, err := os.Stat(filepath.Join(dir, name))
		if err != nil {
			continue
		}
		st, ok := info.Sys().(*syscall.Stat_t)
		if !ok {
			continue
		}
		out = append(out, ContainerCgroup{
			CgroupID:      st.Ino,
			ContainerName: containerNameFromScope(name, pod),
		})
	}
	return out, nil
}

// podCgroupSlicePath returns the cgroup v2 slice path (relative to the
// cgroup root) for the given pod. Layout depends on QoS class:
//
//	Guaranteed: kubepods.slice/kubepods-pod<UID>.slice
//	Burstable:  kubepods.slice/kubepods-burstable.slice/kubepods-burstable-pod<UID>.slice
//	BestEffort: kubepods.slice/kubepods-besteffort.slice/kubepods-besteffort-pod<UID>.slice
//
// UIDs in the path use underscores instead of dashes (systemd's escaping
// rule). Returns "" if pod is missing UID or QoS class — caller skips.
func podCgroupSlicePath(pod *corev1.Pod) string {
	if pod == nil || pod.UID == "" {
		return ""
	}
	uid := strings.ReplaceAll(string(pod.UID), "-", "_")
	switch pod.Status.QOSClass {
	case corev1.PodQOSGuaranteed:
		return filepath.Join("kubepods.slice",
			"kubepods-pod"+uid+".slice")
	case corev1.PodQOSBurstable:
		return filepath.Join("kubepods.slice",
			"kubepods-burstable.slice",
			"kubepods-burstable-pod"+uid+".slice")
	case corev1.PodQOSBestEffort:
		return filepath.Join("kubepods.slice",
			"kubepods-besteffort.slice",
			"kubepods-besteffort-pod"+uid+".slice")
	default:
		// QoS class isn't populated until the pod has been scheduled and
		// the kubelet has run admission. Caller retries on next update.
		return ""
	}
}

// isContainerScope returns true for cgroup directory names that represent
// a container (vs. other entries like memory.* control files or unrelated
// subdirs). We accept the three common CRI runtime prefixes; unknown ones
// (e.g. lxc, podman) get skipped — extend the list as we encounter them
// in customer clusters.
func isContainerScope(name string) bool {
	if !strings.HasSuffix(name, ".scope") {
		return false
	}
	prefixes := []string{"cri-containerd-", "docker-", "crio-"}
	for _, p := range prefixes {
		if strings.HasPrefix(name, p) {
			return true
		}
	}
	return false
}

// containerNameFromScope tries to recover a human-readable container name
// from the scope filename by matching its container ID against the pod's
// ContainerStatuses. Falls back to the truncated scope filename if no
// match (e.g. container hasn't reported status yet).
func containerNameFromScope(scope string, pod *corev1.Pod) string {
	// Scope filenames look like "cri-containerd-<64hex>.scope" or
	// "docker-<64hex>.scope". Extract the hex ID.
	id := scope
	id = strings.TrimSuffix(id, ".scope")
	for _, p := range []string{"cri-containerd-", "docker-", "crio-"} {
		id = strings.TrimPrefix(id, p)
	}
	if pod != nil {
		for _, cs := range pod.Status.ContainerStatuses {
			// ContainerID is like "containerd://<64hex>"; compare suffix.
			if strings.HasSuffix(cs.ContainerID, id) {
				return cs.Name
			}
		}
		for _, cs := range pod.Status.InitContainerStatuses {
			if strings.HasSuffix(cs.ContainerID, id) {
				return cs.Name
			}
		}
	}
	if len(id) > 12 {
		id = id[:12]
	}
	return "container-" + id
}
