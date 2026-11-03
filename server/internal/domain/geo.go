package domain

import "math"

const (
	metersPerDegree = 111_320.0
	// CellSizeMeters는 한마디를 모아 조회하는 칸의 한 변 길이다.
	CellSizeMeters = 300.0
)

// 경도 방향 칸 폭을 한국 중간 위도(37.5°)로 고정해 칸 크기를 단순하게 맞춘다.
var lonScale = math.Cos(37.5 * math.Pi / 180)

// Cell은 300m 칸의 좌표다.
type Cell struct {
	X int
	Y int
}

// CellOf는 위경도가 속한 300m 칸을 돌려준다.
func CellOf(lat, lon float64) Cell {
	return Cell{
		X: int(math.Floor(lon * metersPerDegree * lonScale / CellSizeMeters)),
		Y: int(math.Floor(lat * metersPerDegree / CellSizeMeters)),
	}
}

// Neighbors는 자기 칸과 둘레 8칸을 돌려준다.
func (c Cell) Neighbors() []Cell {
	cells := make([]Cell, 0, 9)
	for dy := -1; dy <= 1; dy++ {
		for dx := -1; dx <= 1; dx++ {
			cells = append(cells, Cell{X: c.X + dx, Y: c.Y + dy})
		}
	}
	return cells
}

// AngleDiff는 두 방향의 차이를 0~180도로 돌려준다. 359°와 1°는 2°다.
func AngleDiff(a, b float64) float64 {
	return math.Abs(math.Mod(a-b+540, 360) - 180)
}

// DistanceMeters는 두 지점 사이의 거리(하버사인)를 미터로 돌려준다.
func DistanceMeters(lat1, lon1, lat2, lon2 float64) float64 {
	const earthRadius = 6_371_000.0
	p1, p2 := toRad(lat1), toRad(lat2)
	dp, dl := toRad(lat2-lat1), toRad(lon2-lon1)
	h := math.Sin(dp/2)*math.Sin(dp/2) + math.Cos(p1)*math.Cos(p2)*math.Sin(dl/2)*math.Sin(dl/2)
	return 2 * earthRadius * math.Asin(math.Sqrt(h))
}

// Bearing은 첫 지점에서 둘째 지점을 바라보는 방위를 0~360도로 돌려준다. 북쪽 0, 시계 방향.
func Bearing(lat1, lon1, lat2, lon2 float64) float64 {
	p1, p2 := toRad(lat1), toRad(lat2)
	dl := toRad(lon2 - lon1)
	y := math.Sin(dl) * math.Cos(p2)
	x := math.Cos(p1)*math.Sin(p2) - math.Sin(p1)*math.Cos(p2)*math.Cos(dl)
	return math.Mod(math.Atan2(y, x)*180/math.Pi+360, 360)
}

func toRad(deg float64) float64 { return deg * math.Pi / 180 }
