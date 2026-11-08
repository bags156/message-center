// SPDX-License-Identifier: AGPL-3.0-or-later

package bridge

import "io.github.bags156.messagecenter/bridge/heic"

// HEVCPicture is one HEVC still picture decoded in software, 8 bits per sample.
type HEVCPicture struct{ p *heic.Picture }

// Chroma is 0 for monochrome, 1 for 4:2:0, 2 for 4:2:2 and 3 for 4:4:4.
func (h *HEVCPicture) Chroma() int { return h.p.Chroma }

// Width of plane 0 (Y), 1 (Cb) or 2 (Cr); each row of Plane(i) is this many bytes.
func (h *HEVCPicture) Width(plane int) int {
	if plane < 0 || plane > 2 {
		return 0
	}
	return h.p.Widths[plane]
}

// Height of plane 0 (Y), 1 (Cb) or 2 (Cr).
func (h *HEVCPicture) Height(plane int) int {
	if plane < 0 || plane > 2 {
		return 0
	}
	return h.p.Heights[plane]
}

// Plane returns the samples of plane 0 (Y), 1 (Cb) or 2 (Cr); nil when absent.
func (h *HEVCPicture) Plane(plane int) []byte {
	if plane < 0 || plane > 2 {
		return nil
	}
	return h.p.Planes[plane]
}

// DecodeHEVC decodes an Annex-B HEVC stream (parameter sets and one picture)
// with libde265. It handles the 4:4:4 colour and monochrome transparency layers
// in iPhone Genmoji and stickers, which phone video decoders refuse. The app
// only calls it from an isolated sandbox process, because the input comes from
// whoever sent the message.
func DecodeHEVC(stream []byte) (*HEVCPicture, error) {
	p, err := heic.Decode(stream)
	if err != nil {
		return nil, err
	}
	return &HEVCPicture{p: p}, nil
}
