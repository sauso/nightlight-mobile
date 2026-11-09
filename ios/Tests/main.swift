import Foundation

// main.swift: the entry point of the ios/Tests program (see TestSupport.swift for why there is one). CI builds it with
//   swiftc ios/App/App/EndpointRules.swift ios/App/App/EndpointProbe.swift ios/App/App/DiscoveryRules.swift
//          ios/App/App/DiscoveryState.swift ios/App/App/EndpointChooser.swift
//          ios/App/App/SessionRules.swift ios/App/App/SessionVault.swift ios/App/App/KeychainLogic.swift
//          ios/App/App/RoamRules.swift ios/Tests/*.swift
// and runs it (build-ios.yml). It needs no network, no Simulator, no Keychain and no Capacitor. Exit status: 0 when every
// check passed, 1 when any failed or none ran at all (a runner that runs nothing must not look like a pass).

runEndpointRulesTests()
runEndpointProbeTests()
runDiscoveryRulesTests()
runDiscoveryStateTests()
runEndpointChooserTests()
runSessionRulesTests()
runSessionVaultTests()
runKeychainLogicTests()
runRoamRulesTests()
runPromptGateTests()

say("")
if testRun.checks == 0 {
    say("FAILED: no checks ran.")
    exit(1)
}
if testRun.failures.isEmpty {
    say("OK: \(testRun.tests) tests, \(testRun.checks) checks, none failed.")
    exit(0)
}
say("FAILED: \(testRun.failures.count) of \(testRun.checks) checks, in \(testRun.tests) tests:")
for failure in testRun.failures {
    say("  - \(failure)")
}
exit(1)
