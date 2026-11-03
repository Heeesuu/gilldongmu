package repository

import (
	"context"
	"errors"
	"time"

	"gorm.io/gorm"
	"gorm.io/gorm/clause"

	"gildongmu/server/internal/domain"
	"gildongmu/server/internal/usecase"
)

// MessageRepository는 usecase.MessageRepository의 GORM 구현이다.
type MessageRepository struct {
	db *gorm.DB
}

var _ usecase.MessageRepository = (*MessageRepository)(nil)

func NewMessageRepository(db *gorm.DB) *MessageRepository {
	return &MessageRepository{db: db}
}

func (r *MessageRepository) Create(ctx context.Context, m *domain.Message) error {
	return r.db.WithContext(ctx).Create(m).Error
}

func (r *MessageRepository) Get(ctx context.Context, id string) (*domain.Message, error) {
	var m domain.Message
	err := r.db.WithContext(ctx).First(&m, "id = ?", id).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, domain.ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &m, nil
}

// Delete는 반응 기록과 한마디(음성·글자 포함)를 지운다. UnitOfWork 안에서 부른다.
func (r *MessageRepository) Delete(ctx context.Context, id string) error {
	db := r.db.WithContext(ctx)
	if err := db.Where("message_id = ?", id).Delete(&domain.Thanks{}).Error; err != nil {
		return err
	}
	if err := db.Where("message_id = ?", id).Delete(&domain.Listen{}).Error; err != nil {
		return err
	}
	return db.Where("id = ?", id).Delete(&domain.Message{}).Error
}

func (r *MessageRepository) FindPublicInCells(ctx context.Context, cells []domain.Cell, now time.Time, excludeDeviceID string) ([]*domain.Message, error) {
	if len(cells) == 0 {
		return nil, nil
	}
	minX, maxX, minY, maxY := cells[0].X, cells[0].X, cells[0].Y, cells[0].Y
	for _, c := range cells[1:] {
		minX, maxX = min(minX, c.X), max(maxX, c.X)
		minY, maxY = min(minY, c.Y), max(maxY, c.Y)
	}
	var messages []*domain.Message
	err := r.db.WithContext(ctx).
		Omit("Audio"). // 목록에는 음성을 싣지 않는다.
		Where("cell_x BETWEEN ? AND ?", minX, maxX).
		Where("cell_y BETWEEN ? AND ?", minY, maxY).
		Where("review IN ?", []domain.Review{domain.ReviewClean, domain.ReviewProfanity}).
		Where("expires_at > ?", now).
		Where("device_id <> ?", excludeDeviceID).
		Find(&messages).Error
	return messages, err
}

func (r *MessageRepository) ListenedIDs(ctx context.Context, deviceID string, ids []string) (map[string]bool, error) {
	listened := make(map[string]bool)
	if len(ids) == 0 {
		return listened, nil
	}
	var found []string
	err := r.db.WithContext(ctx).Model(&domain.Listen{}).
		Where("device_id = ? AND message_id IN ?", deviceID, ids).
		Distinct().Pluck("message_id", &found).Error
	for _, id := range found {
		listened[id] = true
	}
	return listened, err
}

func (r *MessageRepository) CountUploadsSince(ctx context.Context, deviceID string, since time.Time) (int64, error) {
	var count int64
	err := r.db.WithContext(ctx).Model(&domain.Message{}).
		Where("device_id = ? AND created_at >= ?", deviceID, since).
		Count(&count).Error
	return count, err
}

func (r *MessageRepository) ListByDevice(ctx context.Context, deviceID string, limit int) ([]*domain.Message, error) {
	var messages []*domain.Message
	err := r.db.WithContext(ctx).Omit("Audio").
		Where("device_id = ?", deviceID).
		Order("created_at DESC").Limit(limit).
		Find(&messages).Error
	return messages, err
}

func (r *MessageRepository) FindPendingIDs(ctx context.Context, limit int) ([]string, error) {
	var ids []string
	err := r.db.WithContext(ctx).Model(&domain.Message{}).
		Where("review = ?", domain.ReviewPending).
		Order("created_at").Limit(limit).
		Pluck("id", &ids).Error
	return ids, err
}

func (r *MessageRepository) SaveReview(ctx context.Context, id string, o usecase.ReviewOutcome) error {
	return r.db.WithContext(ctx).Model(&domain.Message{}).Where("id = ?", id).
		Updates(map[string]any{
			"transcript":    o.Transcript,
			"review":        o.Review,
			"kind":          o.Kind,
			"review_reason": o.Reason,
			"expires_at":    o.ExpiresAt,
		}).Error
}

func (r *MessageRepository) AddThanks(ctx context.Context, messageID, deviceID string, at time.Time) (bool, error) {
	result := r.db.WithContext(ctx).
		Clauses(clause.OnConflict{DoNothing: true}).
		Create(&domain.Thanks{MessageID: messageID, DeviceID: deviceID, CreatedAt: at})
	return result.RowsAffected == 1, result.Error
}

func (r *MessageRepository) IncrementThanks(ctx context.Context, id string) error {
	return r.db.WithContext(ctx).Model(&domain.Message{}).Where("id = ?", id).
		UpdateColumn("thanks_count", gorm.Expr("thanks_count + ?", 1)).Error
}

func (r *MessageRepository) AddListen(ctx context.Context, l *domain.Listen) error {
	return r.db.WithContext(ctx).Create(l).Error
}

func (r *MessageRepository) ApplyListen(ctx context.Context, id string, completed bool, expiresAt *time.Time) error {
	updates := map[string]any{"skip_count": gorm.Expr("skip_count + ?", 1)}
	if completed {
		updates = map[string]any{"listen_count": gorm.Expr("listen_count + ?", 1)}
		if expiresAt != nil {
			updates["expires_at"] = *expiresAt
		}
	}
	return r.db.WithContext(ctx).Model(&domain.Message{}).Where("id = ?", id).UpdateColumns(updates).Error
}
