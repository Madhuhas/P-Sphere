// swift-tools-version:6.0
// PSphere's sync engine in Swift, for the iPhone and iPad app. It speaks exactly the same protocol
// as the Kotlin core in ../../shared, so an iPad links and backs up with the Android and desktop apps.
// It also builds and runs on Linux, which is how it's tested against the Kotlin core.
import PackageDescription

let package = Package(
    name: "PSphereKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "PSphereKit", targets: ["PSphereKit"]),
        .executable(name: "psphere-swift", targets: ["psphere-swift"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-nio.git", from: "2.65.0"),
        .package(url: "https://github.com/apple/swift-nio-ssl.git", from: "2.27.0"),
        .package(url: "https://github.com/apple/swift-crypto.git", "3.0.0"..<"5.0.0"),
        .package(url: "https://github.com/apple/swift-certificates.git", from: "1.5.0"),
    ],
    targets: [
        .target(
            name: "PSphereKit",
            dependencies: [
                .product(name: "NIOCore", package: "swift-nio"),
                .product(name: "NIOPosix", package: "swift-nio"),
                .product(name: "NIOSSL", package: "swift-nio-ssl"),
                .product(name: "Crypto", package: "swift-crypto"),
                .product(name: "X509", package: "swift-certificates"),
            ]
        ),
        .executableTarget(name: "psphere-swift", dependencies: ["PSphereKit"]),
        .testTarget(name: "PSphereKitTests", dependencies: ["PSphereKit"]),
    ]
)
