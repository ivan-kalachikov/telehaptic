"""Picks the typing-test phrases from analysis/phrase_pool.txt (python analysis/phrases.py).

Goal: useful touches for learning. Keys should come up evenly, with extra weight on the keys mixed up
most in the first typing tests (3, 6, 9, 2, 5), and as many different key-to-key moves as possible,
since the thumb comes from the previous key. Greedy: each step takes the phrase that adds the most,
divided by the square root of its length. The output is the list in TypingController.PHRASES.
"""
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent
KEYPAD = {'2': 'abc', '3': 'def', '4': 'ghi', '5': 'jkl', '6': 'mno', '7': 'pqrs', '8': 'tuv', '9': 'wxyz'}
DIGIT = {c: d for d, s in KEYPAD.items() for c in s}
WEIGHT = {k: 1.0 for k in '0123456789'} | {'3': 2, '6': 2, '9': 2, '2': 1.5, '5': 1.5}
COUNT = 36


def keys(phrase):
    """Key presses for a phrase: letters, 0 between words, 1 (full stop) at the end."""
    return list('0'.join(''.join(DIGIT[c] for c in w) for w in phrase.split())) + ['1']


def pick(pool, n=COUNT):
    chosen, kc, bc = [], Counter(), Counter()

    def gain(p):
        s, k2, b2, g = keys(p), kc.copy(), bc.copy(), 0.0
        for c in s:
            g += WEIGHT[c] / (1 + k2[c]); k2[c] += 1
        for a, b in zip(s, s[1:]):
            g += 1.5 / (1 + b2[a + b]); b2[a + b] += 1
        return g / len(s) ** 0.5

    while len(chosen) < n:
        p = max((q for q in pool if q not in chosen), key=gain)
        chosen.append(p)
        s = keys(p); kc.update(s); bc.update(a + b for a, b in zip(s, s[1:]))
    return chosen


def main():
    words = {l.split()[0] for l in open(ROOT.parent / 'android/app/src/main/assets/en_words.txt')}
    pool = [l.strip() for l in open(ROOT / 'phrase_pool.txt') if l.strip()]
    missing = sorted({w for p in pool for w in p.split() if w not in words})
    if missing:
        print('words not in the word list:', missing)
    chosen = pick([p for p in pool if all(w in words for w in p.split())])
    k = Counter(c for p in chosen for c in keys(p)); total = sum(k.values())
    moves = {a + b for p in chosen for a, b in zip(keys(p), keys(p)[1:])}
    print(f'{len(chosen)} phrases, {total} presses, {len(moves)} different key-to-key moves')
    print('share per key: ' + ' '.join(f'{c}:{k[c] / total * 100:.0f}%' for c in '0123456789'))
    for p in chosen:
        print(f'"{p}",')


if __name__ == '__main__':
    main()
