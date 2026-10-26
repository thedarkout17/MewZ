import SwiftUI
import MewZPhone

/// The iPhone app. Everything you see and hear is Kotlin (../phone); this just shows it.
@main
struct MewZApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                // Compose draws edge to edge and handles the notch, home bar and keyboard itself.
                .ignoresSafeArea()
                .preferredColorScheme(.dark)
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
