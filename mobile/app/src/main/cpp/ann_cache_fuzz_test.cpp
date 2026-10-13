#include "ann_index.h"
#include <cassert>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <iterator>
#include <random>
#include <unistd.h>

/** Deterministic corrupt-cache fuzzing under ASan/UBSan. SHA checks run earlier in Java. */
int main(){
    constexpr int D=512,N=256;std::mt19937 random(410935);std::normal_distribution<float> normal;
    hoyoung::AnnIndex graph(D,N);std::vector<float> query(D),v(D);for(int i=0;i<N;i++){for(float& x:v)x=normal(random);graph.add(i+1,v.data(),D);if(i==N/2)query=v;}
    char file[]="/tmp/hoyoung-ann-fuzz-XXXXXX";int fd=mkstemp(file);assert(fd>=0);close(fd);graph.save(file);
    std::ifstream saved(file,std::ios::binary);std::vector<char> valid((std::istreambuf_iterator<char>(saved)),{});saved.close();int rejected=0,accepted=0;
    for(int trial=0;trial<500;trial++){
        std::vector<char> corrupt=valid;std::uniform_int_distribution<size_t> offset(0,corrupt.size()-1);size_t where=offset(random);corrupt[where]^=static_cast<char>(1U<<(random()%8));
        if(trial<96){where=trial;corrupt=valid;corrupt[where]^=static_cast<char>(1U<<(trial%8));}
        if(trial%20==0)corrupt.resize(static_cast<size_t>(random())%corrupt.size());
        {std::ofstream out(file,std::ios::binary|std::ios::trunc);out.write(corrupt.data(),corrupt.size());}
        try{hoyoung::AnnIndex loaded(D,1);loaded.load(file,N);auto result=loaded.search(query.data(),D,20,200);for(auto hit:result){assert(hit.label>0);assert(std::isfinite(hit.similarity));}accepted++;}
        catch(const std::exception&){rejected++;}
    }
    std::remove(file);assert(rejected>100);std::cout<<"HOYOUNG_NATIVE_ANN_FUZZ_OK trials=500 rejected="<<rejected<<" structurallyValidMutations="<<accepted<<" sanitizerErrors=0"<<std::endl;
}
