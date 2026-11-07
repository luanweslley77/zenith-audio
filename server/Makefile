CXX ?= g++
CXXFLAGS ?= -std=c++20 -O3 -Wall -Wextra -pthread -Iinclude -I../protocol $(shell pkg-config --cflags libpipewire-0.3 opus)
override CXXFLAGS += -MMD -MP
LDFLAGS ?= $(shell pkg-config --libs libpipewire-0.3 opus) -pthread

# Optional RNNoise noise suppression. When the development package is not
# installed, the server still builds and simply runs without the denoiser.
RNNOISE_CFLAGS ?= $(shell pkg-config --cflags rnnoise 2>/dev/null)
RNNOISE_LIBS ?= $(shell pkg-config --libs rnnoise 2>/dev/null)
ifneq ($(strip $(RNNOISE_LIBS)),)
override CXXFLAGS += -DZENITH_HAVE_RNNOISE $(RNNOISE_CFLAGS)
override LDFLAGS += $(RNNOISE_LIBS)
endif

# Rebuild objects when RNNoise availability changes, even without a clean.
RNNOISE_STATE := $(if $(strip $(RNNOISE_LIBS)),on,off)
RNNOISE_STAMP := .rnnoise-state

SRCS = src/main.cpp \
       src/pipewire_capture.cpp \
       src/pipewire_mic.cpp \
       src/opus_encoder.cpp \
       src/network_server.cpp \
       src/audio_engine.cpp

OBJS = $(SRCS:.cpp=.o)
DEPS = $(OBJS:.o=.d)
TARGET = bin/zenith-server

.PHONY: all clean FORCE

all: $(TARGET)

$(RNNOISE_STAMP): FORCE
	@echo '$(RNNOISE_STATE)' | cmp -s - $@ || echo '$(RNNOISE_STATE)' > $@

FORCE:

$(OBJS): $(RNNOISE_STAMP)

$(TARGET): $(OBJS)
	@mkdir -p bin
	$(CXX) $(OBJS) $(LDFLAGS) -o $(TARGET)
	@echo "Built $(TARGET) successfully."

%.o: %.cpp
	$(CXX) $(CXXFLAGS) -c $< -o $@

clean:
	rm -rf $(OBJS) $(DEPS) $(RNNOISE_STAMP) bin

-include $(DEPS)
