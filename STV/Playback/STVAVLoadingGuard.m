#import "STVAVLoadingGuard.h"
#import <AVFoundation/AVFoundation.h>

@implementation STVAVLoadingGuard

+ (void)guarded:(void (^)(void))block {
    @try {
        if (block) { block(); }
    }
    @catch (NSException *e) {
        NSLog(@"[STV] guarded AVAssetResourceLoadingRequest setup: %@ | %@", e.name, e.reason ?: @"(no reason)");
    }
}

+ (void)finish:(AVAssetResourceLoadingRequest *)request error:(NSError *)error {
    @try {
        if (request.isCancelled || request.isFinished) { return; }
        if (error) {
            [request finishLoadingWithError:error];
        } else {
            [request finishLoading];
        }
    }
    @catch (NSException *e) {
        // AVFCore raises NSInternalInconsistencyException for bookkeeping races
        // (request retired concurrently, finished twice, or finished while the
        // delegate callback has not returned). Swift cannot catch ObjC exceptions;
        // without this guard the exception escapes the dispatch block ->
        // std::terminate -> SIGABRT and the whole app is killed.
        // Dropping this single resource load is safe: at worst that episode fails.
        NSLog(@"[STV] guarded finishLoading: %@ | %@", e.name, e.reason ?: @"(no reason)");
    }
}

@end
