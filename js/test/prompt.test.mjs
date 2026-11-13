// node --test js/test/prompt.test.mjs
import test from 'node:test'
import assert from 'node:assert/strict'
import { screenSection, systemPromptSection } from '../packages/plugin-guard/index.js'

const screen = { name: 'screen_read', group: 'screen', risk: 'read', needsUnlock: true }
const launch = { name: 'app_launch', group: 'apps', risk: 'write', needsUnlock: true }
const guide = { name: 'app_guide', group: 'apps', risk: 'read', needsUnlock: false }
const note = { name: 'app_note', group: 'apps', risk: 'read', needsUnlock: false }
const alarm = { name: 'alarm_set', group: 'system', risk: 'write', needsUnlock: false }

test('screen operation section only when screen tools are registered', () => {
  const withScreen = systemPromptSection('zh-CN', [screen, launch, guide, alarm])
  for (const s of ["on the user's screen", 'screen: "main"', 'hand_to_user(reason, kind)', 'loop-guard', 'pacing', 'app_launch / app_open_link', 'app_guide(app)'])
    assert.ok(withScreen.includes(s), s)
  assert.ok(!withScreen.includes('vscreen_start') && !withScreen.includes('displayId'))
  assert.ok(!systemPromptSection('zh-CN', [alarm]).includes('Operating apps on screen'))
})

test('effect phrases match what the screen tools return', () => {
  const text = systemPromptSection('zh-CN', [screen])
  // ScreenTools.after(): "Effect: nothing changed on screen." / "Effect: switched to <label> (<package>)."
  assert.ok(text.includes('"Effect: nothing changed on screen."'))
  assert.ok(text.includes('"Effect: switched to <app>"'))
  assert.ok(!text.includes('"No change"') && !text.includes('"Switched to'))
})

test('app tools are only mentioned when the Apps group is enabled', () => {
  const text = screenSection([screen, alarm])
  assert.ok(text.includes('Operating apps on screen'))
  assert.ok(!text.includes('app_launch') && !text.includes('app_open_link') && !text.includes('app_guide'))
})

test('app note guidance only when app_note is registered', () => {
  const withNote = screenSection([screen, launch, guide, note])
  for (const s of ['app_note', 'ONE concise factual note', 'no personal data', 'confirm it', 'update or remove'])
    assert.ok(withNote.includes(s), s)
  assert.ok(!screenSection([screen, launch, guide]).includes('app_note'))
  assert.ok(withNote.endsWith('\n\n'))
})

test('picker guidance only when screen_set_wheel is registered', () => {
  const wheel = { name: 'screen_set_wheel', group: 'screen', risk: 'write', needsUnlock: true }
  const withWheel = screenSection([screen, wheel])
  assert.ok(withWheel.includes('screen_set_wheel(node, value)'))
  assert.ok(withWheel.endsWith('\n\n'))
  assert.ok(!screenSection([screen]).includes('screen_set_wheel'))
})

test('long tasks: no "next round" claims, progress kept with todo_write (#37)', () => {
  const text = systemPromptSection('zh-CN', [screen])
  for (const s of ['keep calling tools until the task is done', 'there is no automatic next round', 'todo_write', 'does not repeat actions', 'what remains'])
    assert.ok(text.includes(s), s)
})
