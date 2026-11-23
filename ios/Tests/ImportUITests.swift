import XCTest

final class ImportUITests: XCTestCase {
    private func openPicker(_ app: XCUIApplication, _ action: String = "选择多张磁盘镜像") {
        let button = app.buttons["library.addGame"]
        XCTAssertTrue(button.waitForExistence(timeout: 10)); button.tap()
        let choice = app.buttons[action]
        XCTAssertTrue(choice.waitForExistence(timeout: 5)); choice.tap()
        XCTAssertTrue(app.buttons["Cancel"].waitForExistence(timeout: 15))
    }
    private func choose(_ app: XCUIApplication, _ name: String) {
        let file = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@ OR label BEGINSWITH %@", name, name + ".")).firstMatch
        XCTAssertTrue(file.waitForExistence(timeout: 15), app.debugDescription)
        file.tap()
        let open = app.buttons["Open"].firstMatch
        if open.waitForExistence(timeout: 2) && open.isEnabled { open.tap() }
        else { let add = app.buttons["Add"].firstMatch; if add.exists && add.isEnabled { add.tap() } }
    }
    private func addSelected(_ app: XCUIApplication, _ title: String) {
        let confirm = app.buttons["library.confirmImport"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 15), app.debugDescription)
        XCTAssertTrue(confirm.isEnabled); confirm.tap()
        XCTAssertTrue(app.staticTexts[title].firstMatch.waitForExistence(timeout: 20), app.debugDescription)
    }
    private func record(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = name; shot.lifetime = .keepAlways; add(shot)
    }
    private func ready(_ app: XCUIApplication) {
        let wheel = app.buttons["展开工具栏"]
        XCTAssertTrue(wheel.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true"), object: wheel)], timeout: 30), .completed)
    }
    private func states(_ app: XCUIApplication) {
        ready(app); app.buttons["展开工具栏"].tap(); app.buttons["菜单"].tap()
        let button = app.buttons["即时存档 / 读档"]
        XCTAssertTrue(button.waitForExistence(timeout: 5)); button.tap()
        XCTAssertTrue(app.buttons["state.slot.1"].waitForExistence(timeout: 5))
    }
    func testImportAndPresentationFlow() {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "org.necmobile.ios")
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US", "--import-ui-fixtures"]
        app.launch()
        XCTAssertTrue(app.buttons["PC-88"].waitForExistence(timeout: 10)); app.buttons["PC-88"].tap()
        openPicker(app); record(app, "document-picker"); app.buttons["Cancel"].tap()
        openPicker(app); choose(app, "UI-Disks")
        XCTAssertTrue(app.buttons["library.confirmImport"].waitForExistence(timeout: 15)); record(app, "import-name")
        app.buttons["取消"].tap()
        openPicker(app); choose(app, "UI-Disks"); addSelected(app, "UI-Disks")
        record(app, "disk-imported")
        openPicker(app, "游戏 ZIP"); choose(app, "UI-Archive"); addSelected(app, "UI-Archive")
        openPicker(app, "游戏文件夹"); choose(app, "UIFolder"); addSelected(app, "UIFolder")
        openPicker(app); choose(app, "UI-Bad")
        XCTAssertTrue(app.buttons["library.confirmImport"].waitForExistence(timeout: 15)); app.buttons["library.confirmImport"].tap()
        XCTAssertTrue(app.alerts["操作未完成"].waitForExistence(timeout: 15)); record(app, "import-error"); app.buttons["知道了"].tap()

        app.staticTexts["UI-Disks"].firstMatch.tap()
        let remove = app.buttons["移除游戏"]
        if !remove.isHittable { app.swipeUp() }; if !remove.isHittable { app.swipeUp() }
        remove.tap(); XCTAssertTrue(app.alerts["移除游戏？"].waitForExistence(timeout: 5)); app.alerts.buttons["取消"].tap()
        app.buttons["修复磁盘名称编码"].tap()
        XCTAssertTrue(app.buttons["关闭"].waitForExistence(timeout: 5)); app.buttons["关闭"].tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["启动 UI-Disks"].tap()
        states(app); app.buttons["state.slot.1"].tap(); app.buttons["保存当前进度"].tap()
        states(app); app.buttons["state.slot.1"].tap(); app.buttons["保存当前进度"].tap()
        XCTAssertTrue(app.alerts["覆盖存档？"].waitForExistence(timeout: 5)); record(app, "overwrite-confirmation"); app.alerts.buttons["取消"].tap()
        app.buttons["返回游戏"].tap(); ready(app)
        app.buttons["展开工具栏"].tap(); app.buttons["菜单"].tap(); app.buttons["退出游戏"].tap()
        let exit = app.buttons["保存并退出"]
        XCTAssertTrue(exit.waitForExistence(timeout: 5)); exit.tap()
        XCTAssertTrue(app.buttons["library.addGame"].waitForExistence(timeout: 20))

        app.buttons["PC-98"].tap(); openPicker(app, "DOS 游戏目录"); choose(app, "UIDOS")
        XCTAssertTrue(app.buttons["dos.commit"].waitForExistence(timeout: 20)); record(app, "dos-wizard"); app.buttons["取消"].tap()
        openPicker(app, "DOS 游戏目录"); choose(app, "UIDOS")
        XCTAssertTrue(app.buttons["dos.commit"].waitForExistence(timeout: 20))
        let commit = app.buttons["dos.commit"]
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true"), object: commit)], timeout: 20), .completed)
        commit.tap(); XCTAssertTrue(app.staticTexts["UIDOS"].firstMatch.waitForExistence(timeout: 30)); record(app, "dos-imported")
        app.terminate()
    }
}
