// SPDX-License-Identifier: AGPL-3.0-or-later

// Package heic decodes single HEVC still pictures in software with libde265
// (LGPL-3.0, vendored in libde265/). Phone video decoders handle ordinary
// 4:2:0 photos, but not the 4:4:4 colour and monochrome transparency layers
// iPhones use for Genmoji and stickers.
package heic

/*
#cgo CPPFLAGS: -I${SRCDIR} -DHAVE_POSIX_MEMALIGN -DHAVE_STDINT_H
#cgo CXXFLAGS: -std=c++11 -O2 -w
#cgo android LDFLAGS: -static-libstdc++
#cgo linux,!android LDFLAGS: -lstdc++ -lpthread
#include <stdlib.h>
#include "hevc_still.h"
*/
import "C"

import (
	"errors"
	"unsafe"
)

// Picture is one decoded picture with 8 bits per sample. Chroma is 0 for
// monochrome, 1 for 4:2:0, 2 for 4:2:2 and 3 for 4:4:4. Planes holds Y, then
// Cb and Cr (absent for monochrome), each Widths[i] bytes per row.
type Picture struct {
	Chroma  int
	Widths  [3]int
	Heights [3]int
	Planes  [3][]byte
}

// maxStream bounds the input so a hostile file can't make the decoder allocate without limit.
const maxStream = 32 << 20

// Decode decodes the first picture of an Annex-B HEVC stream (parameter sets
// followed by one picture).
func Decode(stream []byte) (*Picture, error) {
	if len(stream) == 0 || len(stream) > maxStream {
		return nil, errors.New("heic: stream empty or too large")
	}
	in := C.CBytes(stream)
	defer C.free(in)
	var pic C.hevc_picture
	var msg [200]C.char
	if C.hevc_decode_still((*C.uint8_t)(in), C.size_t(len(stream)), &pic, &msg[0], C.int(len(msg))) != 0 {
		return nil, errors.New("heic: " + C.GoString(&msg[0]))
	}
	defer C.hevc_free(&pic)
	out := &Picture{Chroma: int(pic.chroma)}
	planes := 3
	if out.Chroma == 0 {
		planes = 1
	}
	for i := 0; i < planes; i++ {
		w, h := int(pic.width[i]), int(pic.height[i])
		if w <= 0 || h <= 0 || pic.plane[i] == nil {
			return nil, errors.New("heic: picture plane missing")
		}
		out.Widths[i], out.Heights[i] = w, h
		out.Planes[i] = C.GoBytes(unsafe.Pointer(pic.plane[i]), C.int(w*h))
	}
	return out, nil
}
