#!/usr/bin/python

import datetime
import numpy
import re
import sys

labels = []
x = []
y = []

with open(sys.argv[1]) as f:
    cur_y = None
    for line in f:
        if line.startswith("2"):
            # timestamp
            cur_y = []
            x.append(datetime.datetime.fromisoformat(line[:line.index('.')]))
            y.append(cur_y)
        else:
            match = re.match(r'(\w+):\s+(\d+) kB\s*', line)
            if not match:
                continue
            if match.group(1) not in ('MemFree',): # in ('MemTotal', 'MemAvailable', 'VmallocTotal', 'CmaTotal', 'CmaFree', 'Inactive', 'Mapped', 'CommitLimit', 'Committed_AS', 'Hugetlb', 'Hugememsize'):
                continue
            if len(x) == 1:
                labels.append(match.group(1))
            cur_y.append(int(match.group(2)))

y = numpy.transpose(y)

import matplotlib.pyplot as plt

print(labels)

ax = plt.subplot()
ax.stackplot(x, y, labels=labels)
ax.legend()

plt.show()