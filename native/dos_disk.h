#pragma once
#include <optional>
#include <string>
#include <vector>
namespace NecDos {
struct File { std::string host, name; };
std::string info(const std::string& path);
std::string autoexec(const std::string& path);
std::string inspect(const std::string& path);
void floppy(const std::string& source, const std::string& destination,
            const std::optional<std::string>& autoexec, const std::optional<std::string>& config,
            const std::vector<File>& files, const std::optional<std::string>& label);
void build(const std::string& source, const std::string& destination, const std::vector<File>& files,
           const std::string& startup, const std::string& autoexec);
}
