---
name: travel-preparation-skill
description: Generate campus travel preparation advice by combining class schedule and weather for a specific day. Use when the user asks what to bring, whether to carry an umbrella, how to prepare before going out, or similar campus preparation questions such as "明天上课需要带什么", "今天出门要准备什么", or "早八要不要带伞".
---

# Travel Preparation Skill

## Overview

Use this skill for campus "出行准备" decisions. Resolve the date first, then call `getSchedule`, then call `getWeather`, and only then generate advice. Do not guess schedule or weather.

## Workflow

Follow this sequence exactly:

1. Parse the target date from the user message.
2. Call `getSchedule` with that date.
3. Call `getWeather` with that date.
4. Generate preparation advice from the returned course list, time, and weather.
5. Reply in clear Chinese with structured bullet points.

## Date Resolution

Resolve the date before any tool call.

- If the user does not specify a date, use today.
- If the user says `明天`, use current date plus one day.
- If the user says `今天`, use current date.
- If the user gives an explicit date, use that exact date.

Pass the resolved date to both tools in `YYYY-MM-DD` format.

## Required Tools

Call both tools every time this skill is used.

### `getSchedule`

Purpose: fetch course information for the target date.

Input:

```json
{
  "date": "YYYY-MM-DD"
}
```

Expected fields to use:

- `courseName`
- `classroom`
- `startTime`
- `endTime`

Extract:

- all course names
- whether any class starts at or before `08:00`

### `getWeather`

Purpose: fetch weather information for the target date.

Input:

```json
{
  "date": "YYYY-MM-DD"
}
```

Expected fields to use:

- `weather`
- `temperature`
- `humidity`

Extract:

- weather type such as `rainy`, `sunny`, `cloudy`
- temperature

## Decision Rules

Apply all matching rules. Do not skip schedule lookup or weather lookup.

### Study items

- If any course name contains `实验`, `实训`, or `实验课`, suggest `实验报告、实验服`.
- If any course name contains `计算机`, `编程`, or `数据库`, suggest `电脑、U盘`.
- If any course name contains `数学`, `高数`, or `线代`, suggest `教材、笔记本`.
- If no course matches the above rules, suggest `笔记本、文具`.

### Weather items

- If `weather = rainy`, must suggest `雨伞`.
- If `weather = sunny`, suggest `防晒衣 / 防晒霜`.
- If temperature is above `30°C`, suggest `清凉穿着、多喝水`.

### Time reminder

- If there is any course at or before `08:00`, add `早八课程，建议提前出门`.

## Output Requirements

The final answer must include all of the following:

- date explanation
- course overview
- weather summary
- preparation advice

Use clear bullet points. Keep the answer natural and concise.

Recommended structure:

```text
日期：...
课程：...
天气：...
建议：
- 学习用品：...
- 出行物品：...
- 时间提醒：...
```

## Example

User:

```text
明天早八要带什么？
```

Response pattern:

```text
日期：明天（2026-04-09）
课程：高等数学，08:00-09:40，教一101
天气：小雨，25°C，湿度80%
建议：
- 学习用品：高数教材、笔记本
- 出行物品：雨伞
- 时间提醒：早八课程，建议提前出门
```

## System Prompt Constraint

When integrating this skill into an agent, include guidance equivalent to the following:

```text
你是一个校园智能助手，在处理“出行准备”相关问题时，必须：
1. 优先调用 getSchedule 获取课表。
2. 再调用 getWeather 获取天气。
3. 严格按照规则生成建议。
4. 不允许凭空猜测课程或天气。
5. 输出必须结构清晰、分点说明。
```
