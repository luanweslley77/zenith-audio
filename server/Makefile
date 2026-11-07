CXX ?= g++
CXXFLAGS ?= -std=c++20 -O3 -Wall -Wextra -pthread -Iinclude -I../protocol $(shell pkg-config --cflags libpipewire-0.3 opus)
CXXFLAGS += -MMD -MP
LDFLAGS ?= $(shell pkg-config --libs libpipewire-0.3 opus) -pthread

SRCS = src/main.cpp \
       src/pipewire_capture.cpp \
       src/pipewire_mic.cpp \
       src/opus_encoder.cpp \
       src/network_server.cpp \
       src/audio_engine.cpp

OBJS = $(SRCS:.cpp=.o)
DEPS = $(OBJS:.o=.d)
TARGET = bin/zenith-server

.PHONY: all clean

all: $(TARGET)

$(TARGET): $(OBJS)
	@mkdir -p bin
	$(CXX) $(OBJS) $(LDFLAGS) -o $(TARGET)
	@echo "Built $(TARGET) successfully."

%.o: %.cpp
	$(CXX) $(CXXFLAGS) -c $< -o $@

clean:
	rm -rf $(OBJS) $(DEPS) bin

-include $(DEPS)
