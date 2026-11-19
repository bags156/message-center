#include <jni.h>
#include <string>
#include <memory>
#include <stdexcept>
#include "quality_core.h"
// All access is serialized on Translation.worker. Only verified app-owned model paths enter here.
static std::unique_ptr<QualityModel> model;
static std::string loadedPath;
static std::string bytes(JNIEnv* e,jbyteArray a){if(!a)return {};int n=e->GetArrayLength(a);std::string s(n,'\0');e->GetByteArrayRegion(a,0,n,reinterpret_cast<jbyte*>(s.data()));return s;}
extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_bags156_messagecenter_QualityNative_translate(JNIEnv* e,jclass,jbyteArray prefix,jbyteArray words,jbyteArray suffix,jbyteArray file,jobject cancelled){
    try{auto path=bytes(e,file);auto cls=e->GetObjectClass(cancelled);auto method=e->GetMethodID(cls,"getAsBoolean","()Z");if(!method)return nullptr;
        auto stop=[&](){bool value=e->CallBooleanMethod(cancelled,method);return value||e->ExceptionCheck();};
        if(stop())return nullptr;
        if(!model||path!=loadedPath){model.reset();loadedPath.clear();model=std::make_unique<QualityModel>(path);loadedPath=path;}
        std::string text=model->translate(bytes(e,prefix),bytes(e,words),bytes(e,suffix),stop);
        auto out=e->NewByteArray(text.size());if(out)e->SetByteArrayRegion(out,0,text.size(),reinterpret_cast<const jbyte*>(text.data()));return out;
    }catch(const std::exception& ex){if(!e->ExceptionCheck())e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),ex.what());return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_io_github_bags156_messagecenter_QualityNative_unload(JNIEnv*,jclass){model.reset();loadedPath.clear();}

extern "C" JNIEXPORT jboolean JNICALL Java_io_github_bags156_messagecenter_QualityNative_loaded(JNIEnv*,jclass){return model?JNI_TRUE:JNI_FALSE;}
