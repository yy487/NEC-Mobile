import XCTest

final class ImportUITests: XCTestCase {
    func testAddGameOpensFilePicker() {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "org.necmobile.ios")
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        let addButton = app.buttons["library.addGame"]
        XCTAssertTrue(addButton.waitForExistence(timeout: 15))
        XCTAssertTrue(addButton.isEnabled)
        addButton.tap()
        let images = app.buttons["选择多张磁盘镜像"]
        XCTAssertTrue(images.waitForExistence(timeout: 5))
        let menu = XCTAttachment(screenshot: app.screenshot())
        menu.name = "add-game-menu"; menu.lifetime = .keepAlways; add(menu)
        images.tap()
        let cancel = app.buttons["Cancel"]
        XCTAssertTrue(cancel.waitForExistence(timeout: 15))
        let picker = XCTAttachment(screenshot: app.screenshot())
        picker.name = "document-picker"; picker.lifetime = .keepAlways; add(picker)
        cancel.tap()
        XCTAssertTrue(app.buttons["library.addGame"].waitForExistence(timeout: 5))
        app.buttons["library.addGame"].tap()
        XCTAssertTrue(images.waitForExistence(timeout: 5))
        app.terminate()
    }
}
