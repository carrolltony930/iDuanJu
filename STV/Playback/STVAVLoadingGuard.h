#import <Foundation/Foundation.h>

/// Objective-C shim that catches AVFCore exceptions Swift cannot catch.
///
/// AVFCore raises an *Objective-C* NSInternalInconsistencyException when an
/// AVAssetResourceLoadingRequest is finished in a state it does not expect
/// (already retired/cancelled, finished twice, or finished synchronously from
/// inside the resourceLoader delegate callback on iOS 17). Swift has no way to
/// catch an ObjC exception: it escapes the dispatch block, hits std::terminate
/// and aborts the whole process (SIGABRT). Wrapping the work in @try/@catch
/// turns that into "this one load fails", never an app crash.
///
/// Verified against the 2026-11-13 1.8(15) crash log:
///   lastExceptionBacktrace: objc_exception_throw ->
///   -[AVAssetResourceLoadingRequest finishLoadingWithError:] ->
///   HLSPlayback.finishKey(_:id:)  on queue stv.hls.loader
@interface STVAVLoadingGuard : NSObject
+ (void)guarded:(void (^ _Nonnull)(void))block;
@end
