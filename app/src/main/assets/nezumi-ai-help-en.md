# Nezumi AI Help

Welcome to Nezumi AI! This app is a private AI assistant that runs entirely offline on your Android device. This help page walks you through the basics — how to use the app, how the AI works, and the safety features that keep it enjoyable to use.

## Table of Contents

1.  [About Nezumi AI](#1-about-nezumi-ai)
    *   [What is Nezumi AI](#11-what-is-nezumi-ai)
    *   [Main Features](#12-main-features)
2.  [Getting Started](#2-getting-started)
    *   [AI Chat](#21-ai-chat)
    *   [Image Generation](#22-image-generation)
    *   [Adding Custom Models](#23-adding-custom-models)
    *   [Using Presets](#24-using-presets)
3.  [Skills](#3-skills)
    *   [What is a Skill](#31-what-is-a-skill)
    *   [How to Add a Skill](#32-how-to-add-a-skill)
    *   [How to Create a Skill](#33-how-to-create-a-skill)
    *   [Managing Skills](#34-managing-skills)
4.  [Safety Features](#4-safety-features)
    *   [Nezumi Safety System](#41-nezumi-safety-system)
    *   [Why Content Is Blocked](#42-why-content-is-blocked)
5.  [Privacy](#5-privacy)
6.  [About Network Access](#6-about-network-access)
7.  [Troubleshooting](#7-troubleshooting)
    *   [Model Fails to Load](#71-model-fails-to-load)
    *   [The App Feels Slow](#72-the-app-feels-slow)
    *   [Image Generation Fails](#73-image-generation-fails)
    *   [Out of Storage](#74-out-of-storage)
    *   [Memory Warning Appears](#75-memory-warning-appears)
8.  [FAQ](#8-faq)
    *   [Do I need the internet](#81-do-i-need-the-internet)
    *   [Which models can I use](#82-which-models-can-i-use)
    *   [Why does the answer change](#83-why-does-the-answer-change)
    *   [Why is image generation refused](#84-why-is-image-generation-refused)
9.  [Version Info](#9-version-info)
    *   [Current Version](#91-current-version)
    *   [Changelog](#92-changelog)
10. [Planned Features](#10-planned-features)

## 1. About Nezumi AI

### 1.1. What is Nezumi AI

Nezumi AI is a privacy-first AI chat application for Android that runs without an internet connection. Because all AI inference happens on your device, your data never leaves it — you can use AI safely and privately.

### 1.2. Main Features

*   **AI Chat**: Have natural conversations with a large language model (LLM).
*   **Image Generation**: Generate images from text using an on-device AI model.
*   **Fully On-Device**: All AI processing runs on your device, so no internet is required and your data stays private.
*   **Presets and Skills**: Combine "presets" (which bundle a model, system prompt, and tools per use case) with "Skills" (which give the model extra knowledge and workflows) to shape the assistant's behavior.

## 2. Getting Started

### 2.1. AI Chat

1.  **Download a model**: On first launch — or any time from the **model button** at the top of the screen — pick the AI model you want (for example Gemma 4 2B/4B) and download it. Download time depends on the model size.
2.  **Select the model**: Once the download is complete, pick the model you want to use from the **model button**.
3.  **Start chatting**: Go back to the chat screen, type a message, and start talking to the AI.

### 2.2. Image Generation

1.  **Select a model**: Before using image generation, open the **Image Generation** screen from the sidebar and make sure an image-generation model is selected.
2.  **Enter a prompt**: On the image-generation screen, describe the image you want **in English**. More specific and detailed prompts tend to produce results closer to what you imagined.
    *   **Tip**: If you can't come up with a good prompt, you can ask the AI chat feature something like "Write an English prompt to generate an image of ___".
3.  **Generate**: Tap the generate button after entering your prompt. Depending on your device, generation may take a while.

### 2.3. Adding Custom Models

In addition to the models offered by default, Nezumi AI lets you add and use custom models you have downloaded yourself. From the **Model management** screen in Settings (or the **model button** menu), choose "Import custom model" and point it at a model file on your device (for example a GGUF file or a LiteRT `.task` file). This lets you flexibly try the latest models or ones specialized for particular tasks.

### 2.4. Using Presets

A "preset" bundles a model, system prompt, enabled tools, memory on/off, and other settings into a single reusable configuration. From the **Presets** screen in the sidebar you can select, duplicate, edit, reorder, or delete presets.

*   **Default preset "Nezumi AI"**: A general-purpose preset created automatically on first launch. It follows your app language: when the language is English it uses an English system prompt, when it is Japanese it uses a Japanese one. Manual edits you make are preserved and not overwritten by language switches.
*   **Plain presets**: A locked, ready-to-use preset per downloaded model, with **no system prompt and no tools**. Use it when you want to see the model's raw response behavior.

## 3. Skills

### 3.1. What is a Skill

A **Skill** is an extension that gives the model additional knowledge, procedures, or expertise. A single Skill is made up of:

*   **English `description`**: A short description the model uses to decide *when* to load the Skill.
*   **Markdown files under `references/`**: The actual knowledge or how-to content, loaded on demand.

Skills are **not** loaded into the model at all times. When tool calling is on, the model sees each Skill's description and pulls in the referenced Markdown only when it decides the Skill is relevant. That means you can install many Skills without inflating the token cost of ordinary chats.

### 3.2. How to Add a Skill

1.  Open **Settings → Skills**.
2.  Tap **"Add skill ZIP"** and select a Skill packaged as a ZIP. The ZIP should contain at least a `SKILL.md` (with the skill name and `description` metadata) and, when needed, a `references/` folder.
3.  On success the Skill appears in the list. Any Skill that fails to load is disabled automatically and a notification tells you why.

### 3.3. How to Create a Skill

You can also create a new Skill inside the app.

1.  Tap **Settings → Skills → Create skill**.
2.  Enter the **skill name** (lowercase letters, digits, and hyphens only) and an **English description**. The description is what the model looks at when deciding to load the Skill, so state clearly in one or two sentences **what kind of task it helps with**.
3.  Tap **Add Markdown** to create files under `references/` and write the knowledge or procedures. You can also create nested folders.

### 3.4. Managing Skills

*   **Edit / rename / delete**: Pick a Skill from the list and use the actions on the detail screen. Deletion cannot be undone.
*   **Attach to a preset**: In the preset editor, turn on "Use skills" so that when that preset is active the model can see the `description` of every installed Skill.
*   **Truncated descriptions in the list**: In the preset list, the description line is trimmed to one row so cards stay compact (about 16 characters in the Japanese locale, about 32 characters in the English locale). Open the editor to see the full text.

## 4. Safety Features

### 4.1. Nezumi Safety System

Nezumi AI includes a multi-layer safety system so you can use AI comfortably.

*   **Prompt inspection**: The AI checks the text you enter for a chance of generating unsafe or harmful content before it is sent to the model.
*   **Image inspection**: Generated images are automatically analyzed for unsafe or harmful content.
*   **Layered safety**: These checks run both before content is passed to the AI model and before generated content is shown to you.

### 4.2. Why Content Is Blocked

If the safety system blocks something, it is usually for one of these reasons:

*   **Inappropriate content**: Violence, hate speech, sexual content, or other material considered socially inappropriate.
*   **Dangerous content**: Content that could encourage self-harm, illegal activity, discrimination, or other harm.
*   **Policy violation**: Content that violates the Nezumi AI terms of use or safety policy.

These features do not exist to limit AI expression in general — they exist to keep the app safe to use. Thank you for your understanding.

## 5. Privacy

Nezumi AI is designed with your privacy as the top priority.

*   **Local processing**: AI inference, image generation, and data storage all happen entirely on your device.
*   **AI inference**: Your conversations and generation instructions are handled by the on-device AI model and are not sent to any external server.
*   **Image generation**: Generated images are only saved to your device's internal storage; they are not uploaded anywhere.
*   **Stored data**: Chat history and settings are kept in a secure area on your device.

## 6. About Network Access

Nezumi AI works offline in general, but the following situations require an internet connection:

*   **Model download**: An internet connection is required when downloading a new AI model.
*   **Update checks**: The app talks to the network when checking for app or model updates.
*   **Other necessary traffic**: Anonymous usage statistics for improvements and bug fixes may be sent (only when you opt in).
*   **Cloud models**: When you add and use cloud or self-hosted models (Claude / Gemini / ChatGPT / Ollama / LM Studio), the app talks to that provider. API keys are encrypted with the Android Keystore.

## 7. Troubleshooting

### 7.1. Model Fails to Load

*   **Check storage**: Model files are large, so make sure your device is not running low on storage.
*   **Corrupted model file**: Something may have gone wrong during download. Delete the model once from the model management screen and try downloading it again.
*   **Restart the app**: Fully quit and reopen the app; this often resolves transient issues.

### 7.2. The App Feels Slow

*   **Device specs**: Depending on the AI model and image-generation complexity, your device's RAM or CPU/GPU may not have enough headroom. Consider switching to a lighter model.
*   **Backend setting**: In Settings, confirm that a more efficient backend (NPU/GPU) is selected.
*   **Background apps**: Other apps may be consuming resources; close ones you don't need.

### 7.3. Image Generation Fails

*   **Prompt content**: Unsafe prompts may be blocked by the safety system. Review the wording.
*   **Out of storage**: There may not be enough storage left to save generated images.
*   **Corrupted model**: The image-generation model itself may be corrupted. Try re-downloading it from the model management screen.

### 7.4. Out of Storage

*   **Delete unused models**: From the Model management screen, delete AI models you no longer use to free up space.
*   **Clear app data**: From **Storage management** in Settings you can clear the cache and temporary files (note that this can also erase chat history and other data).

### 7.5. Memory Warning Appears

Nezumi AI runs large AI models directly on your device, so it uses a lot of memory. If you see a "memory warning" or "out of memory" message, try the following:

*   **Switch to a lighter model**: From the model button, pick a smaller AI model (for example a 2B model).
*   **Close background apps**: Other apps may be using a lot of memory. Fully close apps you don't need before using Nezumi AI.
*   **Restart the device**: Rebooting the device frees memory and can stabilize the app.
*   **Device specs**: If your device has less than the recommended 8GB of RAM, memory warnings can happen often. Consider using a device with more RAM.

## 8. FAQ

### 8.1. Do I need the internet

Regular AI chat and image generation work offline. However, an internet connection is required when downloading models, checking for app updates, or using cloud models.

### 8.2. Which models can I use

Models such as Gemma 4 2B/4B and Gemma 3n are available. You can also import custom models (GGUF or LiteRT `.task`) or add cloud models (Claude / Gemini / ChatGPT / Ollama / LM Studio). More supported models may be added over time.

### 8.3. Why does the answer change

The AI predicts the next word probabilistically to generate text, so you may not get the exact same answer to the same question every time. Parameters such as "Temperature" also affect how varied the answers are.

### 8.4. Why is image generation refused

The Nezumi AI safety system may refuse generation when it decides the prompt or the generated image is inappropriate. Please use prompts that comply with the safety policy.

## 9. Version Info

### 9.1. Current Version

Nezumi AI ${appversion}

### 9.2. Changelog

Published on the GitHub Releases page.
https://github.com/mouse0329/nezumi-ai/releases

## 10. Planned Features

Nezumi AI keeps evolving. Support for more AI models, new features, and performance improvements are all planned. Stay tuned!
