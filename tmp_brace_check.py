from pathlib import Path
path = Path(r'C:\Users\mouse\AndroidStudioProjects\nezumiai\app\src\main\java\com\nezumi_ai\presentation\viewmodel\ChatViewModel.kt')
text = path.read_text(encoding='utf-8')
count = 0
state = 'code'
line = 1
i = 0
while i < len(text):
    ch = text[i]
    if state == 'code':
        if ch == '"':
            if text[i:i+3] == '"""':
                state = 'triple'
                i += 2
            else:
                state = 'string'
        elif ch == '\\':
            i += 1
        elif ch == '/' and i+1 < len(text) and text[i+1] == '/':
            state = 'linecomment'
            i += 1
        elif ch == '/' and i+1 < len(text) and text[i+1] == '*':
            state = 'blockcomment'
            i += 1
        elif ch == '{':
            count += 1
        elif ch == '}':
            count -= 1
    elif state == 'string':
        if ch == '\\':
            i += 1
        elif ch == '"':
            state = 'code'
    elif state == 'triple':
        if text[i:i+3] == '"""':
            state = 'code'
            i += 2
    elif state == 'linecomment':
        if ch == '\n':
            state = 'code'
    elif state == 'blockcomment':
        if ch == '*' and i+1 < len(text) and text[i+1] == '/':
            state = 'code'
            i += 1
    if ch == '\n':
        if line % 100 == 0 or line in (1,50,100,150,200,300,400,500,600,700,800,900,1000,1100,1200,1300,1400,1500,1600,1700,1800,1900,2000,2100,2200,2300,2400,2500,2600,2700,2800,2900,3000,3100,3200,3300,3400,3500,3600):
            print(f'LINE {line} COUNT {count}')
        line += 1
    i += 1
print('FINAL BALANCE', count, state)
