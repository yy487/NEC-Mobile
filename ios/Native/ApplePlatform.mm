#import "NECBridge.h"
#import "ApplePlatform.h"
#import <AVFoundation/AVFoundation.h>
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
#include <array>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <mutex>

static AVAudioEngine *audioEngine;
static AVAudioSourceNode *sourceNode;
unsigned necAppleOpenAudio(unsigned requested, unsigned bufferMs, AppleAudioRenderer renderer, AppleAudioError) {
    @autoreleasepool {
        NSError *error = nil;
        AVAudioSession *session = AVAudioSession.sharedInstance;
        if (![session setCategory:AVAudioSessionCategoryPlayback mode:AVAudioSessionModeDefault options:0 error:&error]) return 0;
        if (requested) [session setPreferredSampleRate:requested error:nil];
        [session setPreferredIOBufferDuration:bufferMs / 1000.0 error:nil];
        if (![session setActive:YES error:&error]) return 0;
        double rate = session.sampleRate;
        if (rate < 8000) return 0;
        AVAudioFormat *format = [[AVAudioFormat alloc] initWithCommonFormat:AVAudioPCMFormatFloat32 sampleRate:rate channels:2 interleaved:NO];
        sourceNode = [[AVAudioSourceNode alloc] initWithFormat:format renderBlock:^OSStatus(BOOL *silence, const AudioTimeStamp *, AVAudioFrameCount frames, AudioBufferList *list) {
            std::array<int16_t, 4096 * 2> pcm{};
            for (unsigned offset = 0; offset < frames; offset += 4096) {
                unsigned count = std::min(4096u, frames - offset); renderer(pcm.data(), int32_t(count));
                for (unsigned channel = 0; channel < list->mNumberBuffers; channel++) {
                    auto *output = static_cast<float *>(list->mBuffers[channel].mData);
                    for (unsigned i = 0; i < count; i++) output[offset + i] = pcm[i * 2 + std::min(channel, 1u)] / 32768.0f;
                }
            }
            *silence = NO; return noErr;
        }];
        audioEngine = [[AVAudioEngine alloc] init]; [audioEngine attachNode:sourceNode];
        [audioEngine connect:sourceNode to:audioEngine.mainMixerNode format:format];
        if (![audioEngine startAndReturnError:&error]) { audioEngine = nil; sourceNode = nil; return 0; }
        return unsigned(rate);
    }
}
void necAppleCloseAudio() {
    @autoreleasepool { [audioEngine stop]; if (sourceNode) [audioEngine detachNode:sourceNode]; sourceNode = nil; audioEngine = nil; }
}
std::string necAppleCorePath(int platform) {
    @autoreleasepool {
        NSString *name = platform == 88 ? @"pc88" : @"pc98";
        return [[NSBundle.mainBundle.privateFrameworksPath stringByAppendingPathComponent:[NSString stringWithFormat:@"%@.framework/%@", name, name]] UTF8String];
    }
}

@interface NECDisplayView () {
    id<MTLDevice> _device;
    id<MTLCommandQueue> _queue;
    id<MTLRenderPipelineState> _pipeline;
    id<MTLTexture> _texture;
    std::mutex _frameMutex;
    NSData *_frame;
    unsigned _frameWidth, _frameHeight;
    std::atomic<bool> _scheduled;
}
- (void)receive:(const uint32_t *)pixels width:(unsigned)width height:(unsigned)height;
- (void)render;
@end
static __weak NECDisplayView *display;
@implementation NECDisplayView
+ (Class)layerClass { return CAMetalLayer.class; }
- (instancetype)initWithFrame:(CGRect)frame {
    if ((self = [super initWithFrame:frame])) {
        _aspect = @"4:3"; _scheduled.store(false); self.contentScaleFactor = UIScreen.mainScreen.scale;
        _device = MTLCreateSystemDefaultDevice(); _queue = [_device newCommandQueue];
        CAMetalLayer *layer = (CAMetalLayer *)self.layer; layer.device = _device; layer.pixelFormat = MTLPixelFormatBGRA8Unorm; layer.framebufferOnly = YES;
        layer.contentsScale = self.contentScaleFactor;
        layer.opaque = YES; self.backgroundColor = UIColor.blackColor;
        NSString *shader = @"#include <metal_stdlib>\nusing namespace metal;\nstruct V{float4 p[[position]];float2 uv;};vertex V vertexMain(uint i[[vertex_id]]){float2 p[3]={float2(-1,-1),float2(3,-1),float2(-1,3)};V v;v.p=float4(p[i],0,1);v.uv=float2((p[i].x+1)*.5,1-(p[i].y+1)*.5);return v;}fragment float4 fragmentMain(V v[[stage_in]],texture2d<float> t[[texture(0)]]){constexpr sampler s(filter::nearest,address::clamp_to_edge);return t.sample(s,v.uv);}";
        NSError *error = nil; id<MTLLibrary> library = [_device newLibraryWithSource:shader options:nil error:&error];
        MTLRenderPipelineDescriptor *pipeline = [[MTLRenderPipelineDescriptor alloc] init];
        pipeline.vertexFunction = [library newFunctionWithName:@"vertexMain"]; pipeline.fragmentFunction = [library newFunctionWithName:@"fragmentMain"];
        pipeline.colorAttachments[0].pixelFormat = layer.pixelFormat;
        _pipeline = [_device newRenderPipelineStateWithDescriptor:pipeline error:&error];
        display = self;
    }
    return self;
}
- (void)layoutSubviews { [super layoutSubviews]; ((CAMetalLayer *)self.layer).drawableSize = CGSizeMake(self.bounds.size.width * self.contentScaleFactor, self.bounds.size.height * self.contentScaleFactor); [self render]; }
- (void)receive:(const uint32_t *)pixels width:(unsigned)width height:(unsigned)height {
    { std::lock_guard<std::mutex> lock(_frameMutex); _frame = [NSData dataWithBytes:pixels length:size_t(width) * height * 4]; _frameWidth = width; _frameHeight = height; }
    if (!_scheduled.exchange(true)) dispatch_async(dispatch_get_main_queue(), ^{ self->_scheduled.store(false); [self render]; });
}
- (void)render {
    if (!_pipeline || self.bounds.size.width <= 0 || self.bounds.size.height <= 0) return;
    NSData *frame; unsigned width, height;
    { std::lock_guard<std::mutex> lock(_frameMutex); frame = _frame; width = _frameWidth; height = _frameHeight; }
    if (!frame || !width || !height) return;
    if (!_texture || _texture.width != width || _texture.height != height) {
        MTLTextureDescriptor *descriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:width height:height mipmapped:NO];
        descriptor.usage = MTLTextureUsageShaderRead; _texture = [_device newTextureWithDescriptor:descriptor];
    }
    [_texture replaceRegion:MTLRegionMake2D(0,0,width,height) mipmapLevel:0 withBytes:frame.bytes bytesPerRow:width * 4];
    CAMetalLayer *layer = (CAMetalLayer *)self.layer; id<CAMetalDrawable> drawable = [layer nextDrawable]; if (!drawable) return;
    MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor]; pass.colorAttachments[0].texture = drawable.texture;
    pass.colorAttachments[0].loadAction = MTLLoadActionClear; pass.colorAttachments[0].storeAction = MTLStoreActionStore; pass.colorAttachments[0].clearColor = MTLClearColorMake(0,0,0,1);
    id<MTLCommandBuffer> command = [_queue commandBuffer]; id<MTLRenderCommandEncoder> encoder = [command renderCommandEncoderWithDescriptor:pass];
    double availableW = layer.drawableSize.width, availableH = layer.drawableSize.height;
    double ratio = [_aspect isEqual:@"NATIVE"] || [_aspect isEqual:@"INTEGER"] ? double(width) / height : 4.0 / 3;
    double w = std::min(availableW, availableH * ratio), h = w / ratio;
    if ([_aspect isEqual:@"FILL"]) { w = availableW; h = availableH; }
    if ([_aspect isEqual:@"INTEGER"]) { double scale = std::floor(std::min(availableW / width, availableH / height)); if (scale >= 1) { w = width * scale; h = height * scale; } }
    [encoder setViewport:MTLViewport{(availableW-w)/2,(availableH-h)/2,w,h,0,1}];
    [encoder setRenderPipelineState:_pipeline]; [encoder setFragmentTexture:_texture atIndex:0]; [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    [encoder endEncoding]; [command presentDrawable:drawable]; [command commit];
}
- (NSData *)snapshotPNG {
    NSData *frame; unsigned width, height;
    { std::lock_guard<std::mutex> lock(_frameMutex); frame = _frame; width = _frameWidth; height = _frameHeight; }
    if (!frame) return nil;
    CGDataProviderRef provider = CGDataProviderCreateWithCFData((__bridge CFDataRef)frame); CGColorSpaceRef color = CGColorSpaceCreateDeviceRGB();
    CGImageRef image = CGImageCreate(width,height,8,32,width*4,color,kCGBitmapByteOrder32Big | kCGImageAlphaPremultipliedLast,provider,nullptr,false,kCGRenderingIntentDefault);
    NSData *png = UIImagePNGRepresentation([UIImage imageWithCGImage:image]); CGImageRelease(image); CGColorSpaceRelease(color); CGDataProviderRelease(provider); return png;
}
@end
void necApplePresentFrame(const uint32_t *pixels, unsigned width, unsigned height) {
    @autoreleasepool { NECDisplayView *view = display; [view receive:pixels width:width height:height]; }
}
