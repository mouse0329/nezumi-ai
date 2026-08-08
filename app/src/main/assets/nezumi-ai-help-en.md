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
3.  [Safety Features](#3-safety-features)
    *   [Nezumi Safety System](#31-nezumi-safety-system)
    *   [Why Content Is Blocked](#32-why-content-is-blocked)
4.  [Privacy](#4-privacy)
5.  [About Network Access](#5-about-network-access)
6.  [Troubleshooting](#6-troubleshooting)
    *   [Model Fails to Load](#61-model-fails-to-load)
    *   [The App Feels Slow](#62-the-app-feels-slow)
    *   [Image Generation Fails](#63-image-generation-fails)
    *   [Out of Storage](#64-out-of-storage)
    *   [Memory Warning Appears](#65-memory-warning-appears)
7.  [FAQ](#7-faq)
    *   [Do I need the internet?](#71-do-i-need-the-internet)
    *   [Which models can I use?](#72-which-models-can-i-use)
    *   [Why does the answer change?](#73-why-does-the-answer-change)
    *   [Why is image generation refused?](#74-why-is-image-generation-refused)
8.  [Version Info](#8-version-info)
    *   [Current Version](#81-current-version)
    *   [Changelog](#82-changelog)
9.  [Planned Features](#9-planned-features)

## 1. About Nezumi AI

### 1.1. What is Nezumi AI

Nezumi AI is a privacy-first AI chat application for Android that runs without an internet connection. Because all AI inference happens on your device, your data never leaves it — you can use AI safely and privately.

### 1.2. Main Features

*   **AI Chat**: Have natural conversations with a large language model (LLM).
*   **Image Generation**: Generate images from text using an on-device AI model.
*   **Fully On-Device**: All AI processing runs on your device, so no internet is required and your data stays private.

## 2. Getting Started

### 2.1. AI Chat

1.  **Download a model**: On first launch — or any time from the **model button** at the bottom of the screen — pick the AI model you want (for example Gemma 4 2B/4B) and download it. Download time depends on the model size.
2.  **Select the model**: Once the download is complete, pick the model you want to use from the **model button**.
3.  **Start chatting**: Go back to the chat screen, type a message, and start talking to the AI.

### 2.2. Image Generation

1.  **Select a model**: Before using image generation, make sure an image-generation model is selected from the **model button**.
2.  **Enter a prompt**: On the image-generation screen, describe the image you want **in English**. More specific and detailed prompts tend to produce results closer to what you imagined.
    *   **Tip**: If you can't come up with a good prompt, you can ask the AI chat feature something like "Write an English prompt to generate an image of ___".
3.  **Generate**: Tap the generate button after entering your prompt. Depending on your device, generation may take a while.

### 2.3. Adding Custom Models

In addition to the models offered by default, Nezumi AI lets you add and use custom models you have downloaded yourself. Tap the **model button** at the bottom of the screen and choose the option to import a custom model, then point it at a model file on your device (for example a GGUF or TFLite file). This lets you flexibly try the latest models or ones specialized for particular tasks.

## 3. Safety Features

### 3.1. Nezumi Safety System

Nezumi AI includes a multi-layer safety system so you can use AI comfortably.

*   **Prompt inspection**: The AI checks the text you enter for a chance of generating unsafe or harmful content before it is sent to the model.
*   **Image inspection**: Generated images are automatically analyzed for unsafe or harmful content.
*   **Layered safety**: These checks run both before content is passed to the AI model and before generated content is shown to you.

### 3.2. Why Content Is Blocked

If the safety system blocks something, it is usually for one of these reasons:

*   **Inappropriate content**: Violence, hate speech, sexual content, or other material considered socially inappropriate.
*   **Dangerous content**: Content that could encourage self-harm, illegal activity, discrimination, or other harm.
*   **Policy violation**: Content that violates the Nezumi AI terms of use or safety policy.

These features do not exist to limit AI expression in general — they exist to keep the app safe to use. Thank you for your understanding.

## 4. Privacy

Nezumi AI is designed with your privacy as the top priority.

*   **Local processing**: AI inference, image generation, and data storage all happen entirely on your device.
*   **AI inference**: Your conversations and generation instructions are handled by the on-device AI model and are not sent to any external server.
*   **Image generation**: Generated images are only saved to your device's internal storage; they are not uploaded anywhere.
*   **Stored data**: Chat history and settings are kept in a secure area on your device.

## 5. About Network Access

Nezumi AI works offline in general, but the following situations require an internet connection:

*   **Model download**: An internet connection is required when downloading a new AI model.
*   **Update checks**: The app talks to the network when checking for app or model updates.
*   **Other necessary traffic**: Anonymous usage statistics for improvements and bug fixes may be sent (only when you opt in).

## 6. Troubleshooting

### 6.1. Model Fails to Load

*   **Check storage**: Model files are large, so make sure your device is not running low on storage.
*   **Corrupted model file**: Something may have gone wrong during download. Delete the model once and try downloading it again from the model management screen.
*   **Restart the app**: Fully quit and reopen the app; this often resolves transient issues.

### 6.2. The App Feels Slow

*   **Device specs**: Depending on the AI model and image-generation complexity, your device's RAM or CPU/GPU may not have enough headroom. Consider switching to a lighter model.
*   **Backend setting**: In Settings, confirm that a more efficient backend (NPU/GPU) is selected.
*   **Background apps**: Other apps may be consuming resources; close ones you don't need.

### 6.3. Image Generation Fails

*   **Prompt content**: Unsafe prompts may be blocked by the safety system. Review the wording.
*   **Out of storage**: There may not be enough storage left to save generated images.
*   **Corrupted model**: The image-generation model itself may be corrupted. Try re-downloading it from the model management screen.

### 6.4. Out of Storage

*   **Delete unused models**: Tap the **model button** at the bottom of the screen and delete AI models you no longer use to free up space.
*   **Clear app data**: Clearing the app cache or data can free storage temporarily (note that this can also erase chat history and other data).

### 6.5. Memory Warning Appears

Nezumi AI runs large AI models directly on your device, so it uses a lot of memory. If you see a "memory warning" or "out of memory" message, try the following:

*   **Switch to a lighter model**: From the **model button** at the bottom of the screen, pick a smaller AI model (for example a 2B model).
*   **Close background apps**: Other apps may be using a lot of memory. Fully close apps you don't need before using Nezumi AI.
*   **Restart the device**: Rebooting the device frees memory and can stabilize the app.
*   **Device specs**: If your device has less than the recommended 8GB of RAM, memory warnings can happen often. Consider using a device with more RAM.

## 7. FAQ

### 7.1. Do I need the internet?

Regular AI chat and image generation work offline. However, an internet connection is required when downloading models or checking for app updates.

### 7.2. Which models can I use?

Models such as Gemma 4 2B/4B and Gemma 3n are available. You can also import custom models. More supported models may be added over time.

### 7.3. Why does the answer change?

The AI predicts the next word probabilistically to generate text, so you may not get the exact same answer to the same question every time. Parameters such as "Temperature" also affect how varied the answers are.

### 7.4. Why is image generation refused?

The Nezumi AI safety system may refuse generation when it decides the prompt or the generated image is inappropriate. Please use prompts that comply with the safety policy.

## 8. Version Info

### 8.1. Current Version

Nezumi AI ${appversion}

### 8.2. Changelog

Detailed changelog is available inside the app under "Settings" → "About".

## 9. Planned Features

Nezumi AI keeps evolving. Support for more AI models, new features, and performance improvements are all planned. Stay tuned!
