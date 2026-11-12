import XCTest

/// 小说 shelf → reader: page turning by tap, menu, 目录 jump, font size relayout.
final class NovelUITests: XCTestCase {
    static let sample = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("STVTests/Fixtures/novel_sample_gbk.txt").path
    var app: XCUIApplication!
    func el(_ id: String) -> XCUIElement { app.descendants(matching: .any)[id].firstMatch }
    func shot(_ name: String) {
        guard let dir = ProcessInfo.processInfo.environment["STV_SHOT_DIR"] else { return }
        try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent(name + ".png"))
    }
    func pageLabel() -> (Int, Int) {
        let parts = el("novel.page").label.components(separatedBy: " / ").compactMap { Int($0) }
        return parts.count == 2 ? (parts[0], parts[1]) : (0, 0)
    }

    func testReadTurnPagesAndJumpChapter() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchEnvironment["STV_HOME_TAB"] = "novel"
        app.launchEnvironment["STV_NOVEL_SEED"] = Self.sample
        app.launch()
        let book = el("novel.book.novel_sample_gbk")
        XCTAssertTrue(book.waitForExistence(timeout: 10), "seeded book not on shelf")
        shot("novel_01_shelf")
        book.tap()
        XCTAssertTrue(el("novel.page").waitForExistence(timeout: 5), "reader did not open")
        let (p0, n0) = pageLabel()
        XCTAssertGreaterThan(n0, 1)
        shot("novel_02_page")

        // Right third → next page; left third → back.
        let win = app.windows.firstMatch
        win.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        XCTAssertEqual(pageLabel().0, p0 + 1, "tap right did not turn the page")
        win.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.5)).tap()
        XCTAssertEqual(pageLabel().0, p0, "tap left did not go back")

        // Middle → menu; 目录 → 第三章.
        win.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(el("novel.menu.目录").waitForExistence(timeout: 3), "menu did not show")
        shot("novel_03_menu")
        el("novel.menu.目录").tap()
        let ch3 = app.buttons["第三章 灯塔"]
        XCTAssertTrue(ch3.waitForExistence(timeout: 3), "目录 sheet missing")
        ch3.tap()
        XCTAssertTrue(app.staticTexts["第三章 灯塔"].waitForExistence(timeout: 3), "did not jump to 第三章")
        XCTAssertEqual(pageLabel().0, 1)

        // 设置: dragging the 字号 slider to the big end re-paginates (more pages for the same chapter).
        win.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        el("novel.menu.设置").tap()
        let size = el("novel.font.size")
        XCTAssertTrue(size.waitForExistence(timeout: 3))
        // Prefs persist between runs: start from the small end, then go big.
        size.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 0.05, thenDragTo: size.coordinate(withNormalizedOffset: CGVector(dx: 0.02, dy: 0.5)))
        let before = pageLabel().1
        size.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.5)).press(forDuration: 0.05, thenDragTo: size.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.5)))
        shot("novel_04_font")
        el("novel.menu.亮度").tap()
        XCTAssertTrue(el("novel.theme.2").waitForExistence(timeout: 3))
        el("novel.theme.2").tap()
        shot("novel_04b_colors")
        el("novel.menu.进度").tap()
        shot("novel_04c_progress")
        el("novel.menu.目录").tap()
        shot("novel_04d_toc")
        win.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.08)).tap()   // close the menu
        XCTAssertGreaterThan(pageLabel().1, before, "bigger font did not add pages")
        shot("novel_05_paper")
    }
}
