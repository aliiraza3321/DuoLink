import re, sys
x = open('screenshots/c.xml', encoding='utf-8', errors='ignore').read()
want = sys.argv[1]
m = re.search(r'text="' + re.escape(want) + r'"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
print('0 0' if not m else '%d %d' % ((int(m.group(1)) + int(m.group(3))) // 2,
                                     (int(m.group(2)) + int(m.group(4))) // 2))
