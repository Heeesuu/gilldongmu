package repository

import (
	"fmt"

	"github.com/glebarez/sqlite"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"

	"gildongmu/server/internal/domain"
)

// Open은 DATABASE_URL이 있으면 PostgreSQL, 없으면 로컬 개발용 SQLite 파일을 연다.
// 운영(Neon 등)과 로컬이 같은 GORM 코드를 쓰도록 하기 위함이다.
func Open(databaseURL, sqlitePath string) (*gorm.DB, string, error) {
	config := &gorm.Config{Logger: logger.Default.LogMode(logger.Warn)}
	if databaseURL != "" {
		db, err := gorm.Open(postgres.Open(databaseURL), config)
		if err != nil {
			return nil, "", fmt.Errorf("PostgreSQL 연결 실패: %w", err)
		}
		return db, "postgres", nil
	}
	db, err := gorm.Open(sqlite.Open(sqlitePath), config)
	if err != nil {
		return nil, "", fmt.Errorf("SQLite 열기 실패: %w", err)
	}
	return db, "sqlite:" + sqlitePath, nil
}

// Migrate는 테이블을 만들거나 컬럼을 추가한다. 운영 DB에서는 팀이 직접 실행 여부를 정한다.
func Migrate(db *gorm.DB) error {
	if err := db.AutoMigrate(&domain.Message{}, &domain.Thanks{}, &domain.Listen{}); err != nil {
		return fmt.Errorf("테이블 마이그레이션 실패: %w", err)
	}
	return nil
}
