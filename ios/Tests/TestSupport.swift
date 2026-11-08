import Foundation

// TestSupport.swift: a tiny assertion runner for ios/Tests. The iOS project has no Xcode test target (the project file is
// hand-edited and the app is built only by CI), so the pure rules (EndpointRules, EndpointProbe's parsing, EndpointChooser)
// are compiled together with these files by swiftc into one plain program and run on the macOS runner (build-ios.yml). The
// program prints every test as it runs, then every failed check with its file and line, and exits non-zero if any check failed
// (main.swift), which fails the CI job.
//
// Output is flushed after every line on purpose: if a test crashes the program, the last "RUN" line in the CI log names it.

final class TestRun {
    var failures: [String] = []
    var checks: Int = 0
    var tests: Int = 0
    var current: String = ""
}

let testRun = TestRun()

func say(_ line: String) {
    print(line)
    fflush(stdout)
}

/// Runs one named test. Its checks are counted; a test with a failed check is reported as FAIL.
func test(_ name: String, _ body: () -> Void) {
    testRun.tests += 1
    testRun.current = name
    say("RUN   " + name)
    let before = testRun.failures.count
    body()
    if testRun.failures.count == before {
        say("PASS  " + name)
    } else {
        say("FAIL  " + name)
    }
}

/// One check. A false condition is recorded with the test's name, the message and where it is.
func check(_ condition: Bool, _ message: String, file: String = #file, line: Int = #line) {
    testRun.checks += 1
    if !condition {
        let place = (file as NSString).lastPathComponent
        testRun.failures.append("\(testRun.current): \(message) [\(place):\(line)]")
    }
}

/// `actual == expected`, with both shown when they differ.
func checkEqual<T: Equatable>(_ actual: T, _ expected: T, _ message: String = "", file: String = #file, line: Int = #line) {
    check(actual == expected, "expected \(expected), got \(actual). " + message, file: file, line: line)
}

/// Milliseconds since `startNs` (a DispatchTime uptime), for the chooser's timing checks.
func elapsedMs(since startNs: UInt64) -> Int {
    let nowNs = DispatchTime.now().uptimeNanoseconds
    return Int((nowNs - startNs) / 1_000_000)
}
