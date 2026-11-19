#pragma once
#include <string>
#include <functional>
#include <memory>
struct llama_model;
class QualityModel {
    llama_model* model_=nullptr;
public:
    explicit QualityModel(const std::string& path);
    ~QualityModel();
    QualityModel(const QualityModel&)=delete;
    QualityModel& operator=(const QualityModel&)=delete;
    std::string translate(const std::string& prefix,const std::string& text,const std::string& suffix,const std::function<bool()>& cancelled);
};
