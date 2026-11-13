#import "STVAVLoadingGuard.h"

@implementation STVAVLoadingGuard

+ (void)guarded:(void (^)(void))block {
    @try {
        if (block) { block(); }
    }
    @catch (NSException *e) {
        // Deliberately swallow: AVFCore's inconsistency exceptions are thrown for
        // bookkeeping races (request cancelled/retired concurrently with our
        // response). Dropping that single resource load is safe; letting it reach
        // the dispatch boundary aborts the process. Log so it stays diagnosable.
        NSLog(@"[STV] guarded AVAssetResourceLoadingRequest: %@ | %@", e.name, e.reason ?: @"(no reason)");
    }
}

@end
