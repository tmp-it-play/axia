#import <Cocoa/Cocoa.h>
#import <Security/Security.h>
#import <Sparkle/Sparkle.h>

#if !defined(__arm64__)
#error Axia supports only arm64 macOS updater bridges.
#endif

typedef void (*AxiaStatusCallback)(int);
typedef void (*AxiaRestartCallback)(void);

// Kept in sync with NativeUpdateEvent in the Kotlin boundary. No payload or secret crosses this ABI.
enum { AxiaReady, AxiaChecking, AxiaDownloading, AxiaUpdateReady, AxiaError, AxiaUnavailable, AxiaInstalling, AxiaNoUpdate };

@interface AxiaSparkleDriver : NSObject <SPUUserDriver, SPUUpdaterDelegate>
@property(nonatomic, assign) AxiaStatusCallback statusCallback;
@property(nonatomic, assign) AxiaRestartCallback restartCallback;
@property(nonatomic, assign) BOOL manualCheck;
@property(nonatomic, assign) BOOL closed;
@property(nonatomic, copy) void (^cancelOperation)(void);
@property(nonatomic, strong) NSAlert *alert;
- (void)emit:(int)event;
@end

static SPUUpdater *AxiaUpdater;
static AxiaSparkleDriver *AxiaDriver;

static void OnMain(void (^operation)(void)) {
    if ([NSThread isMainThread]) operation();
    else dispatch_async(dispatch_get_main_queue(), operation);
}

static void OnMainSync(void (^operation)(void)) {
    if ([NSThread isMainThread]) operation();
    else dispatch_sync(dispatch_get_main_queue(), operation);
}

@implementation AxiaSparkleDriver
- (void)emit:(int)event {
    if (!self.closed && self.statusCallback != NULL) self.statusCallback(event);
}

- (void)showMessage:(NSString *)message detail:(NSString *)detail {
    if (self.closed) return;
    self.alert = [[NSAlert alloc] init];
    self.alert.messageText = message;
    self.alert.informativeText = detail;
    [self.alert addButtonWithTitle:@"확인"];
    [NSApp activateIgnoringOtherApps:YES];
    [self.alert runModal];
    self.alert = nil;
}

- (BOOL)confirmRestart {
    if (self.closed) return NO;
    [self emit:AxiaUpdateReady];
    self.alert = [[NSAlert alloc] init];
    self.alert.messageText = @"업데이트를 다운로드했어요";
    self.alert.informativeText = @"서명 확인을 마친 업데이트를 설치하고 Axia를 재시작할까요? 나중에를 선택해도 계속 작업할 수 있습니다.";
    [self.alert addButtonWithTitle:@"업데이트 후 재시작"];
    [self.alert addButtonWithTitle:@"나중에"];
    self.alert.buttons[0].keyEquivalent = @"";
    self.alert.buttons[1].keyEquivalent = @"\r";
    [NSApp activateIgnoringOtherApps:YES];
    NSModalResponse result = [self.alert runModal];
    self.alert = nil;
    return !self.closed && result == NSAlertFirstButtonReturn;
}

- (void)showUpdatePermissionRequest:(SPUUpdatePermissionRequest *)request reply:(void (^)(SUUpdatePermissionResponse *))reply {
    reply([[SUUpdatePermissionResponse alloc] initWithAutomaticUpdateChecks:YES sendSystemProfile:NO]);
}

- (void)showUserInitiatedUpdateCheckWithCancellation:(void (^)(void))cancellation {
    self.cancelOperation = cancellation;
    [self emit:AxiaChecking];
}

- (void)showUpdateFoundWithAppcastItem:(SUAppcastItem *)item state:(SPUUserUpdateState *)state reply:(void (^)(SPUUserUpdateChoice))reply {
    self.cancelOperation = nil;
    if (self.closed || item.informationOnlyUpdate || item.signingValidationStatus != SPUAppcastSigningValidationStatusSucceeded) {
        reply(SPUUserUpdateChoiceDismiss);
        if (!self.closed) [self emit:AxiaUnavailable];
        return;
    }
    if (state.stage == SPUUserUpdateStageInstalling) {
        // Dismiss would install at quit. Skip cancels even a recovered in-progress install;
        // the explicit startup check below intentionally rediscovers that version next launch.
        reply([self confirmRestart] ? SPUUserUpdateChoiceInstall : SPUUserUpdateChoiceSkip);
        return;
    }
    // Sparkle still owns download, Ed25519 verification, extraction, and installation.
    // Automatically start downloading; the separate ready callback obtains installation consent.
    [self emit:AxiaDownloading];
    reply(SPUUserUpdateChoiceInstall);
}

- (void)showUpdateReleaseNotesWithDownloadData:(SPUDownloadData *)downloadData {}
- (void)showUpdateReleaseNotesFailedToDownloadWithError:(NSError *)error {}
- (BOOL)updater:(SPUUpdater *)updater shouldDownloadReleaseNotesForUpdate:(SUAppcastItem *)item { return NO; }

- (void)showUpdateNotFoundWithError:(NSError *)error acknowledgement:(void (^)(void))acknowledgement {
    [self emit:AxiaNoUpdate];
    if (self.manualCheck) [self showMessage:@"새 업데이트가 없어요" detail:@"현재 앱에 설치할 수 있는 새 버전이 없습니다."];
    self.manualCheck = NO;
    acknowledgement();
}

- (void)showUpdaterError:(NSError *)error acknowledgement:(void (^)(void))acknowledgement {
    [self emit:AxiaError];
    if (self.manualCheck) [self showMessage:@"업데이트를 확인하지 못했어요" detail:@"네트워크 또는 배포 서명을 확인하지 못했습니다. 설정에서 다시 확인해 주세요."];
    self.manualCheck = NO;
    acknowledgement();
}

- (void)showDownloadInitiatedWithCancellation:(void (^)(void))cancellation {
    self.cancelOperation = cancellation;
    [self emit:AxiaDownloading];
}
- (void)showDownloadDidReceiveExpectedContentLength:(uint64_t)length {}
- (void)showDownloadDidReceiveDataOfLength:(uint64_t)length {}
- (void)showDownloadDidStartExtractingUpdate { self.cancelOperation = nil; [self emit:AxiaDownloading]; }
- (void)showExtractionReceivedProgress:(double)progress {}

- (void)showReadyToInstallAndRelaunch:(void (^)(SPUUserUpdateChoice))reply {
    // At this callback Skip cancels the current installation WITHOUT skipping the version.
    // It therefore preserves "later, check again next launch" rather than installing at quit.
    BOOL install = [self confirmRestart];
    reply(install ? SPUUserUpdateChoiceInstall : SPUUserUpdateChoiceSkip);
    if (!install) [self emit:AxiaReady];
}

- (void)showInstallingUpdateWithApplicationTerminated:(BOOL)terminated retryTerminatingApplication:(void (^)(void))retry {
    [self emit:AxiaInstalling];
    if (!terminated && !self.closed && self.restartCallback != NULL) self.restartCallback();
}

- (void)showUpdateInstalledAndRelaunched:(BOOL)relaunched acknowledgement:(void (^)(void))acknowledgement { acknowledgement(); }
- (void)dismissUpdateInstallation { self.cancelOperation = nil; self.manualCheck = NO; }
- (void)showUpdateInFocus { if (self.alert != nil) [self.alert.window makeKeyAndOrderFront:nil]; }
- (void)updaterDidNotFindUpdate:(SPUUpdater *)updater { [self emit:AxiaNoUpdate]; }
- (void)updater:(SPUUpdater *)updater didAbortWithError:(NSError *)error {
    // Sparkle reports no-update and user cancellation through this callback as well.
    if ([error.domain isEqualToString:SUSparkleErrorDomain] && error.code == SUNoUpdateError) [self emit:AxiaNoUpdate];
    else if ([error.domain isEqualToString:SUSparkleErrorDomain] && error.code == SUInstallationCanceledError) [self emit:AxiaReady];
    else [self emit:AxiaError];
}
@end

static BOOL SignedHostMatches(NSString *team) {
    NSBundle *bundle = NSBundle.mainBundle;
    if (![bundle.bundlePath.pathExtension isEqualToString:@"app"] || ![bundle.bundleIdentifier isEqualToString:@"team.themoment.axia"]) return NO;
    SecStaticCodeRef code = NULL;
    if (SecStaticCodeCreateWithPath((__bridge CFURLRef)bundle.bundleURL, kSecCSDefaultFlags, &code) != errSecSuccess) return NO;
    NSString *requirementText = [NSString stringWithFormat:@"anchor apple generic and certificate leaf[subject.OU] = \"%@\"", team];
    SecRequirementRef requirement = NULL;
    OSStatus result = SecRequirementCreateWithString((__bridge CFStringRef)requirementText, kSecCSDefaultFlags, &requirement);
    if (result == errSecSuccess) result = SecStaticCodeCheckValidity(code, kSecCSStrictValidate | kSecCSCheckAllArchitectures, requirement);
    if (requirement != NULL) CFRelease(requirement);
    CFRelease(code);
    return result == errSecSuccess;
}

__attribute__((visibility("default"))) int axia_sparkle_initialize(const char *publicKey, const char *teamId, AxiaStatusCallback status, AxiaRestartCallback restart) {
    if (publicKey == NULL || teamId == NULL || status == NULL || restart == NULL) return 0;
    __block int initialized = 0;
    OnMainSync(^{
        if (AxiaUpdater != nil) return;
        NSString *key = [NSString stringWithUTF8String:publicKey];
        NSString *team = [NSString stringWithUTF8String:teamId];
        if (key == nil || team == nil) return;
        NSData *decoded = [[NSData alloc] initWithBase64EncodedString:key options:0];
        NSDictionary *info = NSBundle.mainBundle.infoDictionary;
        NSRegularExpression *teamPattern = [NSRegularExpression regularExpressionWithPattern:@"^[A-Z0-9]{10}$" options:0 error:nil];
        if (decoded.length != 32 || ![[decoded base64EncodedStringWithOptions:0] isEqualToString:key] ||
            [teamPattern numberOfMatchesInString:team options:0 range:NSMakeRange(0, team.length)] != 1 ||
            ![info[@"SUPublicEDKey"] isEqualToString:key] ||
            ![info[@"SUFeedURL"] isEqualToString:@"https://kimtaeeun.site/axia/updates/macos/arm64/appcast.xml"] ||
            ![info[@"SURequireSignedFeed"] boolValue] || ![info[@"SUVerifyUpdateBeforeExtraction"] boolValue] ||
            info[@"SUSignedFeedFailureExpirationInterval"] == nil || [info[@"SUSignedFeedFailureExpirationInterval"] doubleValue] != 0 ||
            !SignedHostMatches(team)) return;
        AxiaDriver = [[AxiaSparkleDriver alloc] init];
        AxiaDriver.statusCallback = status;
        AxiaDriver.restartCallback = restart;
        AxiaUpdater = [[SPUUpdater alloc] initWithHostBundle:NSBundle.mainBundle applicationBundle:NSBundle.mainBundle userDriver:AxiaDriver delegate:AxiaDriver];
        initialized = 1;
    });
    return initialized;
}

__attribute__((visibility("default"))) void axia_sparkle_start(void) {
    OnMain(^{
        if (AxiaUpdater == nil || AxiaDriver.closed) return;
        AxiaUpdater.automaticallyChecksForUpdates = YES;
        // The user driver downloads automatically but always asks before installation.
        AxiaUpdater.automaticallyDownloadsUpdates = NO;
        AxiaUpdater.sendsSystemProfile = NO;
        NSError *error = nil;
        if (![AxiaUpdater startUpdater:&error]) { [AxiaDriver emit:AxiaError]; return; }
        [AxiaDriver emit:AxiaChecking];
        // Explicit API ignores skipped-version preferences. This makes Later always retry next startup,
        // including a recovered installation, while manualCheck keeps no-update/error UI quiet here.
        [AxiaUpdater checkForUpdates];
    });
}

__attribute__((visibility("default"))) void axia_sparkle_check(void) {
    OnMain(^{
        if (AxiaUpdater == nil || AxiaDriver.closed) return;
        AxiaDriver.manualCheck = YES;
        if (AxiaUpdater.canCheckForUpdates) {
            [AxiaDriver emit:AxiaChecking];
            [AxiaUpdater checkForUpdates];
        } else {
            [AxiaDriver showMessage:@"업데이트를 준비하고 있어요" detail:@"현재 확인 또는 다운로드가 끝나면 설치 여부를 선택할 수 있습니다."];
        }
    });
}

__attribute__((visibility("default"))) void axia_sparkle_close(void) {
    OnMain(^{
        AxiaDriver.closed = YES;
        AxiaDriver.statusCallback = NULL;
        AxiaDriver.restartCallback = NULL;
        if (AxiaDriver.cancelOperation != nil) AxiaDriver.cancelOperation();
        if (AxiaDriver.alert != nil) [NSApp abortModal];
    });
}
