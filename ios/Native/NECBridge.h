#import <UIKit/UIKit.h>
NS_ASSUME_NONNULL_BEGIN
@interface NECDisplayView : UIView
@property(nonatomic, copy) NSString *aspect;
- (nullable NSData *)snapshotPNG;
@end

@interface NECBridge : NSObject
+ (NSString *)start:(NSInteger)platform directory:(NSString *)directory first:(NSString *)first second:(NSString *)second mode:(NSInteger)mode firstIndex:(NSInteger)firstIndex secondIndex:(NSInteger)secondIndex;
+ (void)stop;
+ (void)pause:(BOOL)value;
+ (void)key:(NSInteger)code down:(BOOL)down;
+ (void)mouseX:(NSInteger)x y:(NSInteger)y buttons:(NSInteger)buttons;
+ (void)releaseKeys;
+ (void)reset;
+ (void)flush;
+ (void)audioChanged;
+ (NSInteger)startupState;
+ (NSUInteger)videoGeometry;
+ (NSString *)statusText;
+ (NSString *)startupError;
+ (NSString *)machineInfo;
+ (NSString *)audioInfo;
+ (NSString *)takeWarning;
+ (BOOL)mouseActive;
+ (NSString *)disk:(NSInteger)drive;
+ (NSInteger)diskIndex:(NSInteger)drive;
+ (NSString *)mount:(NSInteger)drive path:(NSString *)path index:(NSInteger)index;
+ (NSString *)state:(NSString *)folder load:(BOOL)load;
+ (BOOL)validSoundFont:(NSString *)path;
+ (NSString *)inspectDisk:(NSString *)path;
+ (NSData *)dosAutoexec:(NSString *)path;
+ (NSString *)buildDos:(NSString *)source target:(NSString *)target files:(NSArray<NSDictionary *> *)files startup:(NSData *)startup autoexec:(NSData *)autoexec;
+ (NSString *)prepareFloppy:(NSString *)source target:(NSString *)target files:(NSArray<NSDictionary *> *)files autoexec:(nullable NSData *)autoexec config:(nullable NSData *)config label:(nullable NSData *)label;
+ (NSString *)gzip:(NSString *)source target:(NSString *)target;
+ (NSString *)gunzip:(NSString *)source target:(NSString *)target;
+ (NSString *)extractZip:(NSString *)source target:(NSString *)target;
@end
NS_ASSUME_NONNULL_END
