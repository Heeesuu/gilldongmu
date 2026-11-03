package moderation

import (
	"testing"

	"gildongmu/server/internal/domain"
)

func TestDictionaryIgnoresSpaces(t *testing.T) {
	d := NewDictionaryFrom("# 주석\n씨발\n\n존나\n")
	cases := map[string]bool{
		"오늘 진짜 씨발 막히네": true,
		"씨 발 뭐야":       true,
		"존 나 예쁘다":      true,
		"다들 오늘도 힘내세요":  false,
		"여기 소떡소떡 맛있어요": false,
	}
	for text, want := range cases {
		if got := d.Contains(text); got != want {
			t.Errorf("Contains(%q) = %v, want %v", text, got, want)
		}
	}
}

func TestEmbeddedDictionaryLoads(t *testing.T) {
	if len(NewDictionary().words) == 0 {
		t.Fatal("badwords.txt가 비어 있습니다")
	}
}

func TestToJudgementRejectsUnknownValues(t *testing.T) {
	if _, err := toJudgement(judgementJSON{Grade: "spam", Kind: "road"}); err == nil {
		t.Error("모르는 grade는 오류여야 합니다")
	}
	if _, err := toJudgement(judgementJSON{Grade: "clean", Kind: "weather"}); err == nil {
		t.Error("모르는 kind는 오류여야 합니다")
	}
	j, err := toJudgement(judgementJSON{Grade: "clean", Kind: "place", Promo: true, Reason: "홍보"})
	if err != nil || j.Grade != domain.ReviewClean || j.Kind != domain.KindPlace || !j.Promo {
		t.Errorf("올바른 값은 그대로 옮겨야 합니다: %+v, %v", j, err)
	}
}
