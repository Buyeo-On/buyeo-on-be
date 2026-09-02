package com.buyeoon.point.application;

import com.buyeoon.common.storage.PublicImageUrlService;
import com.buyeoon.point.repository.PointRankingQueryRepository;
import com.buyeoon.point.repository.PointRankingRow;
import com.buyeoon.point.repository.PointRankingSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** UC-29 누적 포인트 랭킹과 현재 회원의 순위를 읽기 전용으로 조회한다. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PointRankingQueryService {

	private static final int PAGE_SIZE = 20;

	private final PointRankingQueryRepository pointRankingQueryRepository;
	private final PublicImageUrlService publicImageUrlService;

	/** 랭킹 projection과 공개 이미지 URL 생성 seam을 주입받는다. */
	public PointRankingQueryService(PointRankingQueryRepository pointRankingQueryRepository,
			PublicImageUrlService publicImageUrlService) {
		this.pointRankingQueryRepository = pointRankingQueryRepository;
		this.publicImageUrlService = publicImageUrlService;
	}

	/** 현재 페이지 20명, 내 랭킹과 전체 참여자 수를 같은 DB 스냅샷에서 조회한다. */
	public PointRankingListView list(UUID memberId, PointRankingCursor cursor) {
		List<PointRankingRow> rows = cursor == null
				? pointRankingQueryRepository.findFirstPage(PAGE_SIZE + 1)
				: pointRankingQueryRepository.findAfter(cursor, PAGE_SIZE + 1);
		PointRankingSummary summary = pointRankingQueryRepository.findSummary(memberId);
		boolean hasNext = rows.size() > PAGE_SIZE;
		List<PointRankingRow> page = hasNext ? rows.subList(0, PAGE_SIZE) : rows;
		String nextCursor = hasNext
				? new PointRankingCursor(page.getLast().cumulativeEarned(), page.getLast().memberId()).encode()
				: null;
		List<PointRankingItemView> items = page.stream().map(row -> toItem(row, memberId)).toList();
		return new PointRankingListView(items, toMyRanking(summary), summary.totalParticipants(),
				new PageInfoView(nextCursor, hasNext));
	}

	/** 내부 회원 ID와 이미지 키를 API에 노출하지 않는 랭킹 항목으로 바꾼다. */
	private PointRankingItemView toItem(PointRankingRow row, UUID memberId) {
		return new PointRankingItemView(row.rank(), row.displayName(),
				publicImageUrlService.create(row.characterImageKey()), row.cumulativeEarned(),
				row.memberId().equals(memberId));
	}

	/** 참여 여부에 따라 문서화된 내 랭킹 응답을 만든다. */
	private MyPointRankingView toMyRanking(PointRankingSummary summary) {
		if (!summary.eligible()) {
			return new MyPointRankingView(false, null, null, null, 0);
		}
		return new MyPointRankingView(true, summary.rank(), summary.displayName(),
				publicImageUrlService.create(summary.characterImageKey()), summary.cumulativeEarned());
	}

	/** 랭킹 목록, 내 랭킹, 참여자 수와 페이지 정보를 담는 API 응답이다. */
	public record PointRankingListView(List<PointRankingItemView> items, MyPointRankingView myRanking,
			long totalParticipants, PageInfoView page) {
		public PointRankingListView {
			items = List.copyOf(items);
		}
	}

	/** 다른 회원에게 공개할 수 있는 랭킹 항목이다. */
	public record PointRankingItemView(long rank, String displayName, String characterImageUrl, long cumulativeEarned,
			boolean isMe) {
	}

	/** 현재 회원의 랭킹 참여 여부와 순위 정보다. */
	public record MyPointRankingView(boolean eligible, Long rank, String displayName, String characterImageUrl,
			long cumulativeEarned) {
	}

	/** 다음 페이지 커서 존재 여부를 나타내는 페이지 정보다. */
	public record PageInfoView(String nextCursor, boolean hasNext) {
	}
}
