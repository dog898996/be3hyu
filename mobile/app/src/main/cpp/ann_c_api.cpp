// Host-only benchmark entry points use the same header/quantization/search as Android JNI.
#include "ann_index.h"
#include <string>
namespace { thread_local std::string lastError; }
extern "C" {
const char* hoyoung_ann_error(){return lastError.c_str();}
void* hoyoung_ann_create(int dimension,int count,int m,int ef){try{lastError.clear();return new hoyoung::AnnIndex(dimension,count,m,ef);}catch(const std::exception& e){lastError=e.what();return nullptr;}}
void hoyoung_ann_close(void* index){delete static_cast<hoyoung::AnnIndex*>(index);}
int hoyoung_ann_add(void* handle,const float* vectors,const uint64_t* labels,int count){try{lastError.clear();auto* index=static_cast<hoyoung::AnnIndex*>(handle);if(!index||!vectors||!labels||count<0)throw std::invalid_argument("Invalid host ANN batch");for(int i=0;i<count;i++)index->add(labels[i],vectors+i*index->dimension(),index->dimension());return 0;}catch(const std::exception& e){lastError=e.what();return -1;}}
int hoyoung_ann_search(void* handle,const float* query,int k,int ef,uint64_t* labels,float* scores){try{lastError.clear();auto* index=static_cast<hoyoung::AnnIndex*>(handle);if(!index||!query||!labels||!scores)throw std::invalid_argument("Invalid host ANN query");auto hits=index->search(query,index->dimension(),k,ef);for(size_t i=0;i<hits.size();i++){labels[i]=hits[i].label;scores[i]=hits[i].similarity;}return hits.size();}catch(const std::exception& e){lastError=e.what();return -1;}}
int hoyoung_ann_exclude(void* handle,const uint64_t* labels,int count){try{lastError.clear();if(!handle||count<0)throw std::invalid_argument("Invalid host ANN exclusion");std::vector<uint64_t> denied;if(count>0)denied.assign(labels,labels+count);static_cast<hoyoung::AnnIndex*>(handle)->exclude(denied);return 0;}catch(const std::exception& e){lastError=e.what();return -1;}}
int hoyoung_ann_save(void* handle,const char* path){try{lastError.clear();static_cast<hoyoung::AnnIndex*>(handle)->save(path);return 0;}catch(const std::exception& e){lastError=e.what();return -1;}}
int hoyoung_ann_load(void* handle,const char* path,int count){try{lastError.clear();static_cast<hoyoung::AnnIndex*>(handle)->load(path,count);return 0;}catch(const std::exception& e){lastError=e.what();return -1;}}
}
