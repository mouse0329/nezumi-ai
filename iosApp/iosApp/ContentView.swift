import SwiftUI
import shared

/// shared.framework の接続確認用。チャット画面は未実装。
struct ContentView: View {
    private let title = NezumiIosShell.shared.title()
    private let sample = NezumiIosShell.shared.sanitizePreview(
        text: "<start_of_turn>model\nhello<end_of_turn>"
    )

    var body: some View {
        VStack(spacing: 16) {
            Text(title)
                .font(.title)
            Text("shared framework 接続済み")
                .foregroundStyle(.secondary)
            Text(sample)
                .font(.footnote)
                .multilineTextAlignment(.center)
                .padding(.horizontal)
        }
        .padding()
    }
}
