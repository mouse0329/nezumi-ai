import SwiftUI
import shared

/// iOS 初期シェル。チャット / セッション / 設定のタブ構成。
/// shared.framework のロジック接続は各画面の TODO に沿って進める。
struct ContentView: View {
    private let title = NezumiIosShell.shared.title()

    var body: some View {
        TabView {
            NavigationStack {
                ChatView()
                    .navigationTitle(title)
            }
            .tabItem {
                Label("チャット", systemImage: "bubble.left.and.bubble.right")
            }

            NavigationStack {
                SessionListView()
                    .navigationTitle("セッション")
            }
            .tabItem {
                Label("セッション", systemImage: "list.bullet")
            }

            NavigationStack {
                SettingsView()
                    .navigationTitle("設定")
            }
            .tabItem {
                Label("設定", systemImage: "gearshape")
            }
        }
    }
}

/// チャット画面。メッセージ一覧 + 入力欄。
/// 次: ChatSessionRepository / MessageRepository (commonMain) とストリーミング状態を接続。
struct ChatView: View {
    @State private var messages: [ChatLine] = [
        ChatLine(role: "assistant", text: "ネズミAI iOS シェルです。shared のチャットロジック接続はこれから。")
    ]
    @State private var input = ""

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    ForEach(messages) { line in
                        ChatBubble(line: line)
                    }
                }
                .padding()
            }

            HStack(spacing: 8) {
                TextField("メッセージ", text: $input, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...4)
                Button {
                    send()
                } label: {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.title2)
                }
                .disabled(input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding()
        }
    }

    private func send() {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        messages.append(ChatLine(role: "user", text: text))
        input = ""
        // shared の表示正規化を通す（推論接続は次のステップ）
        let sanitized = NezumiIosShell.shared.sanitizePreview(text: text)
        messages.append(ChatLine(role: "assistant", text: "（応答は未接続）\n入力: \(sanitized)"))
    }
}

struct ChatBubble: View {
    let line: ChatLine

    var body: some View {
        HStack {
            if line.role == "user" { Spacer(minLength: 40) }
            Text(line.text)
                .padding(10)
                .background(line.role == "user" ? Color.accentColor.opacity(0.2) : Color.secondary.opacity(0.15))
                .clipShape(RoundedRectangle(cornerRadius: 12))
            if line.role != "user" { Spacer(minLength: 40) }
        }
    }
}

struct ChatLine: Identifiable {
    let id = UUID()
    let role: String
    let text: String
}

/// セッション一覧。次: ChatSessionRepository.getAllSessions() を接続。
struct SessionListView: View {
    var body: some View {
        List {
            Text("セッションはまだありません")
                .foregroundStyle(.secondary)
        }
    }
}

/// 設定。API キー / MCP / モデル選択の入口。
/// API キーは shared の CloudApiKeyStore + iOS Keychain に接続済み。
struct SettingsView: View {
    @State private var providers: [String] = []
    @State private var selectedProvider: String = "openai"
    @State private var apiKey: String = ""
    @State private var status: String = ""

    var body: some View {
        List {
            Section("モデル") {
                NavigationLink("モデル選択") {
                    Text("GGUF / クラウドモデル選択は未接続")
                        .foregroundStyle(.secondary)
                        .padding()
                }
            }
            Section("クラウド (Keychain)") {
                Picker("プロバイダ", selection: $selectedProvider) {
                    ForEach(providers, id: \.self) { id in
                        Text(id).tag(id)
                    }
                }
                SecureField("API キー", text: $apiKey)
                Button("保存") {
                    let ok = NezumiIosShell.shared.setCloudApiKey(
                        providerId: selectedProvider,
                        apiKey: apiKey
                    )
                    status = ok ? "保存しました" : "保存に失敗しました"
                    apiKey = ""
                }
                Text(statusText)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("MCP") {
                NavigationLink("MCP サーバー") {
                    Text("McpServerConfig 管理は未接続")
                        .foregroundStyle(.secondary)
                        .padding()
                }
            }
            Section("権限") {
                Text("カメラ・マイク・写真は Info.plist に利用目的を記載済み")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .onAppear {
            providers = NezumiIosShell.shared.cloudProviderIds()
            if providers.isEmpty { providers = ["openai"] }
            if !providers.contains(selectedProvider) {
                selectedProvider = providers.first ?? "openai"
            }
        }
    }

    private var statusText: String {
        let configured = NezumiIosShell.shared.isCloudProviderConfigured(providerId: selectedProvider)
        let base = NezumiIosShell.shared.cloudBaseUrl(providerId: selectedProvider)
        let flag = configured ? "設定済み" : "未設定"
        return status.isEmpty ? "\(flag) / \(base)" : status
    }
}
