=== 小白免编译上传指南 (3 步直达) ===

1. 在浏览器登录你的 GitHub 账号。
2. 点击右上角 [ + ] 号 -> 选择 [New repository] 创建新仓库，名字填：
   android-remote-keepalive
3. 创建完成后，GitHub 会显示提示：
   "create a new repository on the command line"
   把仓库链接复制下来，在电脑任意地方打开 CMD/终端，依次粘贴执行：
   
   git init
   git add .
   git commit -m "upload"
   git remote add origin https://github.com/你的账号/android-remote-keepalive.git
   git branch -M main
   git push -u origin main
   
4. 推送完成后，进入你的仓库，点击顶栏 [ Actions ] -> 看到云端已经开始自动编译！
