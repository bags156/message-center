#ifndef HEVC_STILL_H
#define HEVC_STILL_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
/* One decoded picture, 8 bits per sample. chroma: 0 = 4:0:0, 1 = 4:2:0, 2 = 4:2:2, 3 = 4:4:4. */
typedef struct {
  int chroma, bits;
  int width[3], height[3], stride[3];
  uint8_t* plane[3];
} hevc_picture;
/* Decodes the first picture of an Annex-B HEVC stream (parameter sets + one picture). 0 on success. */
int hevc_decode_still(const uint8_t* data, size_t len, hevc_picture* out, char* err, int errlen);
void hevc_free(hevc_picture* p);
#ifdef __cplusplus
}
#endif
#endif
