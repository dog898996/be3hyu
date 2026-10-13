#include "ann_index.h"
#include <cassert>
#include <iostream>
#include <random>
#include <cstdio>
#include <unistd.h>

int main(){
    constexpr int D=512;
    hoyoung::QuantizedCosineSpace space(D);std::mt19937 generator(94271);std::normal_distribution<float> normal;
    std::vector<float>a(D),b(D);double worst=0,mean=0;
    for(int sample=0;sample<1000;sample++){
        double aa=0,bb=0,ab=0;for(int i=0;i<D;i++){a[i]=normal(generator);b[i]=normal(generator);aa+=a[i]*a[i];bb+=b[i]*b[i];ab+=a[i]*b[i];}
        double error=std::abs(space.similarity(a.data(),b.data())-ab/std::sqrt(aa*bb));worst=std::max(worst,error);mean+=error;
        assert(std::abs(space.similarity(a.data(),a.data())-1)<1e-5);
    }
    assert(worst<.015);assert(mean/1000<.003);
    hoyoung::AnnIndex graph(D,256);std::vector<float> values(256*D);
    for(int i=0;i<256;i++){for(int j=0;j<D;j++)values[i*D+j]=normal(generator);graph.add(i+1,values.data()+i*D,D);}
    auto result=graph.search(values.data()+117*D,D,20,200);assert(result.front().label==118);assert(result.front().similarity>.9999);
    graph.exclude({118});auto filtered=graph.search(values.data()+117*D,D,20,200);assert(filtered.size()==20);for(auto hit:filtered)assert(hit.label!=118);
    graph.exclude({});
    char filename[]="/tmp/hoyoung-ann-test-XXXXXX";int fd=mkstemp(filename);assert(fd>=0);close(fd);graph.save(filename);
    hoyoung::AnnIndex restored(D,1);restored.load(filename,256);auto copy=restored.search(values.data()+117*D,D,20,200);assert(copy.size()==result.size());for(size_t i=0;i<copy.size();i++){assert(copy[i].label==result[i].label);assert(copy[i].similarity==result[i].similarity);}
    bool bad=false;try{restored.load(filename,255);}catch(const std::runtime_error&){bad=true;}assert(bad);
    {std::ofstream corrupt(filename,std::ios::binary|std::ios::trunc);corrupt<<"invalid";}bad=false;try{restored.load(filename,256);}catch(const std::runtime_error&){bad=true;}assert(bad);std::remove(filename);
    a[0]=std::numeric_limits<float>::quiet_NaN();bad=false;try{graph.add(1000,a.data(),D);}catch(const std::invalid_argument&){bad=true;}assert(bad);
    std::fill(a.begin(),a.end(),0);bad=false;try{graph.add(1000,a.data(),D);}catch(const std::invalid_argument&){bad=true;}assert(bad);
    a[0]=1;uint64_t large=9'007'199'254'740'993ULL;graph.reserve(257);graph.add(large,a.data(),D);assert(graph.search(a.data(),D,1,100).front().label==large);
    graph.exclude({large});assert(graph.search(a.data(),D,1,100).front().label!=large);
    hoyoung::AnnIndex small(4,4);float shortVector[4]={1,0,0,0};small.add(1,shortVector,4);assert(small.search(shortVector,4,1,100).front().label==1);
    std::cout<<"HOYOUNG_NATIVE_ANN_TESTS_OK cases=9 quantizationMeanAbsoluteCosineError="<<mean/1000<<" quantizationWorstAbsoluteCosineError="<<worst<<std::endl;
}
