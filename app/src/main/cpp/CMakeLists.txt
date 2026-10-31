cmake_minimum_required(VERSION 3.18.1)
project(exhish C)
add_library(exhish SHARED term.c untar.c)
target_link_libraries(exhish log m dl)
