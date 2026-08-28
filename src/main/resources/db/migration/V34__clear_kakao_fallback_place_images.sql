-- 카카오 다음 검색 API(/v2/search/image) 결과로 채워졌던 대표이미지를 되돌린다. 검색 결과는 개인
-- 블로그·카페 게시물 사진이라 저작권자 허락이 없고, 장소 일치 여부도 검증되지 않는다. 이후 동기화는
-- 이 폴백을 더 이상 쓰지 않으므로(KakaoImageSearchClient 제거), 재동기화로는 값이 돌아오지 않는다.
UPDATE places
SET source_image_href = NULL
WHERE source_name = 'TOUR_API'
  AND source_image_href LIKE 'https://search%.kakaocdn.net/%';
