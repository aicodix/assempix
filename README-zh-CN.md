# Assempix 简体中文说明

这是 [aicodix/assempix](https://github.com/aicodix/assempix) 的简体中文本地化资源。

Assempix 是一款 Android 应用，可将 COFDMTV 编码的音频信号解码为图像。此汉化仅添加 Android 简体中文字符串资源和中文说明文档，不修改应用逻辑、音频处理流程或解码算法。

## 使用语言

将应用系统语言设为简体中文（中国），应用会在存在对应资源时自动使用简体中文界面；其他语言环境继续使用原有默认资源。

## 传输与解码提示

- 应用需要麦克风权限，以接收并解码音频信号。
- 采样率、声道和音频源等设置会影响设备的音频采集行为；若初始化或录音失败，可尝试更换设置并检查麦克风权限。
- 模式名称、调制方式（例如 8PSK、QPSK）、频率、时长和协议参数保持原样，以免误解技术含义。
- 解码成功后生成的图像会保存到设备存储中，具体位置取决于应用实现和 Android 版本。

## 官方项目与许可证

- 官方仓库：[aicodix/assempix](https://github.com/aicodix/assempix)
- OFDM 调制解调器解码器及规范：[aicodix/modem](https://github.com/aicodix/modem)
- 本地化内容不改变上游项目的原有许可证和版权声明。

## 本地化范围

- Android 界面字符串：`app/src/main/res/values-zh-rCN/strings.xml`
- 中文说明：`README-zh-CN.md`

如发现术语不准确、界面文本遗漏或排版问题，欢迎提交反馈。
