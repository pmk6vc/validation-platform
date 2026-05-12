// Package pod resolves a host PID to its Kubernetes pod UID and container ID
// by parsing /proc/<pid>/cgroup. Best-effort — if the pod can't be resolved
// (PID not in a kubepods cgroup, or PID gone by the time we read), we return
// the empty string and the caller logs without attribution.
//
// COS uses cgroup v2 with the systemd hierarchy; the kubepods slice path
// looks like:
//   /sys/fs/cgroup/kubepods.slice/kubepods-burstable.slice/kubepods-burstable-podabc123_def4_5678_9abc_def012345678.slice/cri-containerd-<container-id>.scope
// Underscores in the pod UID stand in for the canonical UUID dashes — we
// normalise back to dashes in the output.
package pod

import (
	"os"
	"regexp"
	"strings"
	"sync"
)

// Match either "pod<UUID>" (cgroup v1) or "kubepods-...-pod<UUID>" (cgroup v2
// with systemd). Underscores stand in for dashes in cgroup v2 names.
var podRE = regexp.MustCompile(`pod([0-9a-fA-F]{8}[-_][0-9a-fA-F]{4}[-_][0-9a-fA-F]{4}[-_][0-9a-fA-F]{4}[-_][0-9a-fA-F]{12})`)

// Container ID after `cri-containerd-` or `docker-` or `crio-`, ending at `.scope`.
var containerRE = regexp.MustCompile(`(?:cri-containerd-|docker-|crio-)([0-9a-f]{64})`)

type Info struct {
	PodUID      string
	ContainerID string // first 12 chars
}

type Cache struct {
	mu      sync.Mutex
	entries map[uint32]Info
}

func NewCache() *Cache {
	return &Cache{entries: make(map[uint32]Info)}
}

func (c *Cache) Lookup(pid uint32) Info {
	c.mu.Lock()
	if v, ok := c.entries[pid]; ok {
		c.mu.Unlock()
		return v
	}
	c.mu.Unlock()

	info := parseCgroupFile("/proc/" + itoa(pid) + "/cgroup")

	c.mu.Lock()
	c.entries[pid] = info
	c.mu.Unlock()
	return info
}

func parseCgroupFile(path string) Info {
	data, err := os.ReadFile(path)
	if err != nil {
		return Info{}
	}
	return parseCgroupBytes(data)
}

// parseCgroupBytes does the actual regex work — split out so tests can drive
// it with fixtures instead of files on disk.
func parseCgroupBytes(data []byte) Info {
	s := string(data)
	var info Info
	if m := podRE.FindStringSubmatch(s); len(m) > 1 {
		info.PodUID = strings.ReplaceAll(m[1], "_", "-")
	}
	if m := containerRE.FindStringSubmatch(s); len(m) > 1 {
		cid := m[1]
		if len(cid) > 12 {
			cid = cid[:12]
		}
		info.ContainerID = cid
	}
	return info
}

func itoa(u uint32) string {
	if u == 0 {
		return "0"
	}
	var buf [10]byte
	i := len(buf)
	for u > 0 {
		i--
		buf[i] = byte('0' + u%10)
		u /= 10
	}
	return string(buf[i:])
}
