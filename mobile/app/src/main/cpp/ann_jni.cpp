#include <jni.h>
#include <mutex>
#include <unordered_map>
#include "ann_index.h"

namespace {
std::mutex handlesMutex;
std::unordered_map<jlong, std::shared_ptr<hoyoung::AnnIndex>> handles;
jlong nextHandle = 1;
std::shared_ptr<hoyoung::AnnIndex> indexFor(jlong handle) {
    std::lock_guard<std::mutex> lock(handlesMutex);
    auto found = handles.find(handle);
    if (found == handles.end()) throw std::invalid_argument("ANN index is closed");
    return found->second;
}
void fail(JNIEnv* env, const std::exception& error) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
std::vector<float> vectorFrom(JNIEnv* env, jfloatArray input, size_t dimension) {
    if (!input || static_cast<size_t>(env->GetArrayLength(input)) != dimension) throw std::invalid_argument("ANN vector dimension mismatch");
    std::vector<float> values(dimension); env->GetFloatArrayRegion(input, 0, dimension, values.data()); return values;
}
std::string pathFrom(JNIEnv* env, jstring input) {
    if (!input) throw std::invalid_argument("Missing ANN path");
    const char* value = env->GetStringUTFChars(input, nullptr);
    if (!value) throw std::runtime_error("Cannot read ANN path");
    std::string path(value); env->ReleaseStringUTFChars(input, value); return path;
}
}

extern "C" JNIEXPORT jlong JNICALL Java_com_hoyoung_assistant_memory_NativeAnnIndex_nativeCreate(JNIEnv* env, jclass, jint dimensions, jint capacity, jint m, jint ef) {
    try { auto index = std::make_shared<hoyoung::AnnIndex>(dimensions, capacity, m, ef); std::lock_guard<std::mutex> lock(handlesMutex); jlong id = nextHandle++; handles[id] = index; return id; }
    catch (const std::exception& e) { fail(env, e); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_com_hoyoung_assistant_memory_NativeAnnIndex_nativeClose(JNIEnv*, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(handlesMutex); handles.erase(handle);
}
extern "C" JNIEXPORT jint JNICALL Java_com_hoyoung_assistant_memory_NativeAnnIndex_nativeCount(JNIEnv* env, jclass, jlong handle) {
    try { return static_cast<jint>(indexFor(handle)->count()); } catch (const std::exception& e) { fail(env,e); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_com_hoyoung_assistant_memory_NativeAnnIndex_nativeReserve(JNIEnv* env, jclass, jlong handle, jint capacity) {
    try { indexFor(handle)->reserve(capacity); } catch (const std::exception& e) { fail(env,e); }
}
extern "C" JNIEXPORT void JNICALL Java_com_hoyoung_assistant_memory_NativeAnnIndex_nativeAddBatch(JNIEnv* env, jclass, jlong handle, jlongArray inputLabels, jfloatArray inputVectors) {
    try {
        auto index=indexFor(handle);
        if (!inputLabels || !inputVectors) throw std::invalid_argument("Missing ANN batch");
        jsize count=env->GetArrayLength(inputLabels);
        if (count<1 || count>1024 || env->GetArrayLength(inputVectors)!=static_cast<jsize>(count*index->dimension())) throw std::invalid_argument("Invalid ANN batch size");
        std::vector<jlong> labels(count); std::vector<float> values(count*index->dimension());
        env->GetLongArrayRegion(inputLabels,0,count,labels.data()); env->GetFloatArrayRegion(inputVectors,0,values.size(),values.data());
        if(env->ExceptionCheck()) return;
        // Validate the entire batch first, so a malformed vector does not cause a partial native update.
        hoyoung::QuantizedCosineSpace validator(index->dimension());
        for(jsize i=0;i<count;i++){if(labels[i]<=0)throw std::invalid_argument("Invalid ANN label");validator.quantize(values.data()+i*index->dimension(),index->dimension());}
        for(jsize i=0;i<count;i++)index->add(static_cast<uint64_t>(labels[i]),values.data()+i*index->dimension(),index->dimension());
    } catch(const std::exception& e){fail(env,e);}
}
extern "C" JNIEXPORT void JNICALL Java_com_hoyoung_assistant_memory_NativeAnnIndex_nativeExclude(JNIEnv* env, jclass, jlong handle, jlongArray input) {
    try {
        auto index=indexFor(handle); std::vector<uint64_t> values;
        if(input){jsize count=env->GetArrayLength(input);if(count>static_cast<jsize>(hoyoung::AnnIndex::MAX_ELEMENTS))throw std::invalid_argument("Too many ANN exclusions");std::vector<jlong> labels(count);env->GetLongArrayRegion(input,0,count,labels.data());if(env->ExceptionCheck())return;values.reserve(count);for(jlong label:labels){if(label<=0)throw std::invalid_argument("Invalid ANN exclusion label");values.push_back(label);}}
        index->exclude(values);
    } catch(const std::exception& e){fail(env,e);}
}
extern "C" JNIEXPORT jobject JNICALL Java_com_hoyoung_assistant_memory_NativeAnnIndex_nativeSearch(JNIEnv* env, jclass, jlong handle, jfloatArray input, jint k, jint ef) {
    try {
        auto index=indexFor(handle);auto query=vectorFrom(env,input,index->dimension());if(env->ExceptionCheck())return nullptr;
        auto hits=index->search(query.data(),query.size(),k,ef);
        std::vector<jlong> labels;std::vector<float> scores;labels.reserve(hits.size());scores.reserve(hits.size());
        for(const auto& hit:hits){labels.push_back(static_cast<jlong>(hit.label));scores.push_back(hit.similarity);}
        jlongArray ids=env->NewLongArray(labels.size());jfloatArray similarities=env->NewFloatArray(scores.size());if(!ids||!similarities)return nullptr;
        env->SetLongArrayRegion(ids,0,labels.size(),labels.data());env->SetFloatArrayRegion(similarities,0,scores.size(),scores.data());
        jclass result=env->FindClass("com/hoyoung/assistant/memory/NativeAnnIndex$SearchResult");
        return env->NewObject(result,env->GetMethodID(result,"<init>","([J[F)V"),ids,similarities);
    } catch(const std::exception& e){fail(env,e);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_com_hoyoung_assistant_memory_NativeAnnIndex_nativeSave(JNIEnv* env, jclass, jlong handle, jstring path) {
    try { indexFor(handle)->save(pathFrom(env,path)); } catch(const std::exception& e){fail(env,e);}
}
extern "C" JNIEXPORT void JNICALL Java_com_hoyoung_assistant_memory_NativeAnnIndex_nativeLoad(JNIEnv* env, jclass, jlong handle, jstring path, jint count) {
    try { if(count<0)throw std::invalid_argument("Invalid ANN cache count");indexFor(handle)->load(pathFrom(env,path),count); } catch(const std::exception& e){fail(env,e);}
}
