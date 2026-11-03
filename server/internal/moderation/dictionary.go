package moderation

import (
	_ "embed"
	"strings"

	"gildongmu/server/internal/usecase"
)

// badwords.txt는 한 줄에 단어 하나. '#'으로 시작하는 줄은 주석이다. 시연·데이터 담당이 채운다.
//
//go:embed badwords.txt
var badwordsFile string

// Dictionary는 띄어쓰기를 무시하고 비속어가 들어 있는지 본다("씨 발"도 잡기 위함).
type Dictionary struct {
	words []string
}

var _ usecase.ProfanityDictionary = (*Dictionary)(nil)

func NewDictionary() *Dictionary {
	return NewDictionaryFrom(badwordsFile)
}

func NewDictionaryFrom(list string) *Dictionary {
	d := &Dictionary{}
	for _, line := range strings.Split(list, "\n") {
		word := strings.TrimSpace(line)
		if word == "" || strings.HasPrefix(word, "#") {
			continue
		}
		d.words = append(d.words, compact(word))
	}
	return d
}

func (d *Dictionary) Contains(text string) bool {
	t := compact(text)
	for _, w := range d.words {
		if strings.Contains(t, w) {
			return true
		}
	}
	return false
}

func compact(s string) string {
	return strings.ToLower(strings.Join(strings.Fields(s), ""))
}
