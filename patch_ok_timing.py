import re

with open("app/src/main/java/com/nieche/emu/MainActivity.kt", "r") as f:
    content = f.read()

# Replace the specific auto-repeat timing code
old_code = """                                    // 模拟按下和抬起，由于按键是边沿触发，需要间隔交替
                                    // 100ms(按下) -> 100ms(抬起) 的频率
                                    v.postDelayed(this, 80)
                                }
                            }
                            v.postDelayed(repeatTask, 400)"""

new_code = """                                    // 极限频率：30ms 切换一次状态，配合模拟线程的 33ms 循环
                                    v.postDelayed(this, 30)
                                }
                            }
                            v.postDelayed(repeatTask, 50) // 缩短初始延迟，按下 50ms 后立即开始连发"""

content = content.replace(old_code, new_code)

with open("app/src/main/java/com/nieche/emu/MainActivity.kt", "w") as f:
    f.write(content)

