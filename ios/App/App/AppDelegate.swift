import UIKit
import Capacitor
import AVFoundation

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {

    var window: UIWindow?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // Put the audio session in .playback up front (paired with the `audio`
        // UIBackgroundMode) so the WebView's stream audio can continue with the screen
        // off / app backgrounded. Doing it once at launch - rather than reconfiguring
        // mid-stream - is deliberate: calling setActive on an already-playing session
        // interrupts the WebView audio (which is what made switching to Background stop
        // it). On/off vs. background is handled by the web app muting the audio element,
        // so the native side only needs to keep the session category correct.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        // A saved sign-in is kept in the Keychain, which SURVIVES deleting the app, so the first start of a new install removes
        // whatever an earlier install left there (KeychainSessionStore). Queued here, on the FIFO writes queue, so it runs
        // before any session call, which only a web page can make and only once the bridge has loaded one (after this method);
        // off the main thread, so the launch never waits for the Keychain.
        ServerConfigStore.writes.async {
            KeychainSessionStore.purgeIfFreshInstall()
        }
        return true
    }

    func applicationWillResignActive(_ application: UIApplication) {
        // Sent when the application is about to move from active to inactive state. This can occur for certain types of temporary interruptions (such as an incoming phone call or SMS message) or when the user quits the application and it begins the transition to the background state.
        // Use this method to pause ongoing tasks, disable timers, and invalidate graphics rendering callbacks. Games should use this method to pause the game.
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        // Use this method to release shared resources, save user data, invalidate timers, and store enough application state information to restore your application to its current state in case it is terminated later.
        // If your application supports background execution, this method is called instead of applicationWillTerminate: when the user quits.
        // The network watcher stops here: it moves between the server's addresses only while the app is in the foreground
        // (NetworkRoamWatcher), and anything it still has waiting or running is dead from this moment.
        NetworkRoamWatcher.shared.didEnterBackground()
        // Browsing and probing are useful only while the bundled setup page is on screen.
        ServerConfigPlugin.stopDiscoveryForBackground()
    }

    func applicationWillEnterForeground(_ application: UIApplication) {
        // Called as part of the transition from the background to the active state; here you can undo many of the changes made on entering the background.
        // A real return from the background: the next activation checks the address once (NetworkRoamWatcher's start check).
        NetworkRoamWatcher.shared.willEnterForeground()
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        // Restart any tasks that were paused (or not yet started) while the application was inactive. If the application was previously in the background, optionally refresh the user interface.
        // Starts the network watcher, at launch and after each return from the background (not in didFinishLaunching: the
        // first activation does it). Fired again without a trip to the background (Control Center, a call banner, the app
        // switcher) it does nothing while the watcher is already running (NetworkRoamWatcher.didBecomeActive).
        NetworkRoamWatcher.shared.didBecomeActive()
    }

    func applicationWillTerminate(_ application: UIApplication) {
        // Called when the application is about to terminate. Save data if appropriate. See also applicationDidEnterBackground:.
    }

    func application(_ app: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        // Called when the app was launched with a url. Feel free to add additional processing here,
        // but if you want the App API to support tracking app url opens, make sure to keep this call
        return ApplicationDelegateProxy.shared.application(app, open: url, options: options)
    }

    func application(_ application: UIApplication, continue userActivity: NSUserActivity, restorationHandler: @escaping ([UIUserActivityRestoring]?) -> Void) -> Bool {
        // Called when the app was launched with an activity, including Universal Links.
        // Feel free to add additional processing here, but if you want the App API to support
        // tracking app url opens, make sure to keep this call
        return ApplicationDelegateProxy.shared.application(application, continue: userActivity, restorationHandler: restorationHandler)
    }

}
