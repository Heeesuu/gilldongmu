package repository

import (
	"context"

	"gorm.io/gorm"

	"gildongmu/server/internal/usecase"
)

// UnitOfWork는 GORM 트랜잭션 하나에 묶인 저장소를 넘겨준다. fn이 오류를 돌려주면 모두 되돌린다.
type UnitOfWork struct {
	db *gorm.DB
}

var _ usecase.UnitOfWork = (*UnitOfWork)(nil)

func NewUnitOfWork(db *gorm.DB) *UnitOfWork {
	return &UnitOfWork{db: db}
}

func (u *UnitOfWork) Do(ctx context.Context, fn func(repo usecase.MessageRepository) error) error {
	return u.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		return fn(NewMessageRepository(tx))
	})
}
