package repository

import (
	"context"
	"errors"

	"gorm.io/gorm"

	"gildongmu/server/internal/domain"
	"gildongmu/server/internal/usecase"
)

// ProfileRepository는 usecase.ProfileRepository의 GORM 구현이다.
type ProfileRepository struct {
	db *gorm.DB
}

var _ usecase.ProfileRepository = (*ProfileRepository)(nil)

func NewProfileRepository(db *gorm.DB) *ProfileRepository {
	return &ProfileRepository{db: db}
}

func (r *ProfileRepository) Get(ctx context.Context, deviceID string) (*domain.Profile, error) {
	var p domain.Profile
	err := r.db.WithContext(ctx).First(&p, "device_id = ?", deviceID).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &p, nil
}

// Save는 기본 키(device_id)가 같으면 덮어쓰고, 없으면 새로 만든다.
func (r *ProfileRepository) Save(ctx context.Context, p *domain.Profile) error {
	return r.db.WithContext(ctx).Save(p).Error
}
