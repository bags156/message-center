// Decodes a single HEVC still picture with libde265 (handles 4:4:4, 4:2:2 and monochrome,
// which phone video decoders usually refuse; Apple sends Genmoji that way).
#include "hevc_still.h"
#include <libde265/de265.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void fail(char* err, int errlen, const char* what) { if (err && errlen > 0) snprintf(err, errlen, "%s", what); }

extern "C" int hevc_decode_still(const uint8_t* data, size_t len, hevc_picture* out, char* err, int errlen) {
  memset(out, 0, sizeof(*out));
  de265_decoder_context* ctx = de265_new_decoder();
  if (!ctx) { fail(err, errlen, "no decoder"); return -1; }
  de265_error e = de265_push_data(ctx, data, (int)len, 0, NULL);
  if (e != DE265_OK) { fail(err, errlen, de265_get_error_text(e)); de265_free_decoder(ctx); return -1; }
  de265_flush_data(ctx);
  const de265_image* img = NULL;
  int more = 1, rounds = 0;
  while (more && !img && rounds++ < 100000) {
    more = 0;
    e = de265_decode(ctx, &more);
    if (e != DE265_OK && e != DE265_ERROR_WAITING_FOR_INPUT_DATA && !de265_isOK(e)) { fail(err, errlen, de265_get_error_text(e)); break; }
    img = de265_get_next_picture(ctx);
  }
  if (!img) { if (err && !err[0]) fail(err, errlen, "no picture decoded"); de265_free_decoder(ctx); return -1; }
  enum de265_chroma c = de265_get_chroma_format(img);
  out->chroma = c == de265_chroma_mono ? 0 : c == de265_chroma_420 ? 1 : c == de265_chroma_422 ? 2 : 3;
  out->bits = de265_get_bits_per_pixel(img, 0);
  int planes = out->chroma == 0 ? 1 : 3;
  for (int i = 0; i < planes; i++) {
    int stride = 0, w = de265_get_image_width(img, i), h = de265_get_image_height(img, i), bits = de265_get_bits_per_pixel(img, i);
    const uint8_t* src = de265_get_image_plane(img, i, &stride);
    uint8_t* dst = (uint8_t*)malloc((size_t)w * h);
    if (!src || !dst || w <= 0 || h <= 0) { free(dst); hevc_free(out); fail(err, errlen, "bad plane"); de265_free_decoder(ctx); return -1; }
    for (int y = 0; y < h; y++) {
      if (bits <= 8) memcpy(dst + (size_t)y * w, src + (size_t)y * stride, w);
      else { const uint16_t* row = (const uint16_t*)(src + (size_t)y * stride); for (int x = 0; x < w; x++) dst[(size_t)y * w + x] = (uint8_t)(row[x] >> (bits - 8)); }
    }
    out->plane[i] = dst; out->width[i] = w; out->height[i] = h; out->stride[i] = w;
  }
  de265_free_decoder(ctx);
  return 0;
}

extern "C" void hevc_free(hevc_picture* p) { for (int i = 0; i < 3; i++) { free(p->plane[i]); p->plane[i] = NULL; } }
