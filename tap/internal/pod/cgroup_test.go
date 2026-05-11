package pod

import "testing"

// Fixture captured from a real COS pod cgroup file. Cgroup v2 systemd
// layout: kubepods-burstable-pod<UID>.slice with the container scope as
// the final component.
const cosCgroupV2 = `0::/kubepods.slice/kubepods-burstable.slice/kubepods-burstable-pode5923463_9b6f_4e1b_9abc_cc89245073ab.slice/cri-containerd-2d4367c83ddd60c1234abcdef567890abcdef1234567890abcdef1234567890ab.scope
`

// Older cgroup v1 hybrid layout used by some non-COS kernels. The kubepods
// hierarchy uses dashed UUIDs and `pod<UID>` (no `kubepods-` prefix on the
// slice name component).
const cgroupV1 = `12:memory:/kubepods/burstable/pod550e8400-e29b-41d4-a716-446655440000/abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890
0::/kubepods/burstable/pod550e8400-e29b-41d4-a716-446655440000/abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890
`

// Host process — not under kubepods. Parser must yield empty Info (caller
// renders this as "?" in logs).
const hostProcess = `0::/system.slice/sshd.service
`

func TestParseCgroupCOSV2(t *testing.T) {
	info := parseCgroupBytes([]byte(cosCgroupV2))
	if want := "e5923463-9b6f-4e1b-9abc-cc89245073ab"; info.PodUID != want {
		t.Errorf("PodUID = %q, want %q", info.PodUID, want)
	}
	if want := "2d4367c83ddd"; info.ContainerID != want {
		t.Errorf("ContainerID = %q, want %q (first 12 chars only)", info.ContainerID, want)
	}
}

func TestParseCgroupV1(t *testing.T) {
	info := parseCgroupBytes([]byte(cgroupV1))
	if want := "550e8400-e29b-41d4-a716-446655440000"; info.PodUID != want {
		t.Errorf("PodUID = %q, want %q", info.PodUID, want)
	}
	// No `cri-containerd-` / `docker-` / `crio-` prefix in this fixture,
	// so ContainerID is intentionally empty.
	if info.ContainerID != "" {
		t.Errorf("ContainerID = %q, want empty", info.ContainerID)
	}
}

func TestParseCgroupHostProcess(t *testing.T) {
	info := parseCgroupBytes([]byte(hostProcess))
	if info.PodUID != "" || info.ContainerID != "" {
		t.Errorf("host process should yield empty Info, got %+v", info)
	}
}

func TestItoa(t *testing.T) {
	cases := map[uint32]string{0: "0", 1: "1", 1234: "1234", 4294967295: "4294967295"}
	for in, want := range cases {
		if got := itoa(in); got != want {
			t.Errorf("itoa(%d) = %q, want %q", in, got, want)
		}
	}
}

func TestCacheReturnsSameInfo(t *testing.T) {
	c := NewCache()
	// PID 1 is the host's init when hostPID is true; parsing should not
	// panic regardless of what's actually there. We only assert idempotency:
	// the second lookup returns the same struct as the first.
	first := c.Lookup(1)
	second := c.Lookup(1)
	if first != second {
		t.Errorf("cache returned different Info on second lookup: %+v vs %+v", first, second)
	}
}
