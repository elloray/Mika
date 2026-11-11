Wait for text to appear or disappear, an activity class to open, or the phone screen to become stable. The Android port wakes on accessibility events. Use this after an action that starts loading instead of repeatedly observing at a fixed interval.

Prefer appear/disappear/activity conditions for the result you actually need. A stability timeout does not prove the screen is unusable: animation, video and timers may never stop. After a bounded wait, observe and continue if the intended target is identifiable; do not loop on stable waits for decorative motion.
