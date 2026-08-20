package com.buyeoon.point.application;

import com.buyeoon.point.entity.PointTransactionType;
import com.buyeoon.point.repository.PointTransactionRepository;
import com.buyeoon.point.repository.PointTransactionRow;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회원의 포인트 내역을 발생 시각 최신순으로 커서 페이지네이션 조회하는 point 도메인의 공개 seam이다. */
@Service
@Transactional
public class PointTransactionQueryService {

	private static final ZoneId ASIA_SEOUL = ZoneId.of("Asia/Seoul");

	private final PointTransactionRepository pointTransactionRepository;
	private final PointExpirationService pointExpirationService;

	/** 포인트 내역 repository와 요청 시점 만료 확정 seam을 주입받는다. */
	public PointTransactionQueryService(PointTransactionRepository pointTransactionRepository,
			PointExpirationService pointExpirationService) {
		this.pointTransactionRepository = pointTransactionRepository;
		this.pointExpirationService = pointExpirationService;
	}

	/** 발생 시각 최신순, 동일 시각은 내역 ID 순으로 정렬된 한 페이지를 balanceAfter와 함께 조회한다. */
	public PointTransactionListView list(UUID memberId, PointTransactionCursor cursor, int size) {
		pointExpirationService.expireDueSettlementsForActiveMember(memberId);
		List<PointTransactionRow> rows = cursor == null
				? pointTransactionRepository.findFromStart(memberId, size + 1)
				: pointTransactionRepository.findAfter(memberId, cursor.occurredAt(), cursor.transactionId(), size + 1);

		boolean hasNext = rows.size() > size;
		List<PointTransactionRow> page = hasNext ? rows.subList(0, size) : rows;
		String nextCursor = hasNext
				? new PointTransactionCursor(page.getLast().occurredAt(), page.getLast().id()).encode()
				: null;
		List<PointTransactionItemView> items = page.stream().map(this::toView).toList();
		return new PointTransactionListView(items, new PageInfoView(nextCursor, hasNext));
	}

	private PointTransactionItemView toView(PointTransactionRow row) {
		return new PointTransactionItemView(row.id(), row.type(), row.amount(), row.balanceAfter(), row.description(),
				row.occurredAt().atZone(ASIA_SEOUL));
	}

	public record PointTransactionListView(List<PointTransactionItemView> items, PageInfoView page) {
		public PointTransactionListView {
			items = List.copyOf(items);
		}
	}

	public record PointTransactionItemView(UUID transactionId, PointTransactionType type, long amount,
			long balanceAfter, String description, ZonedDateTime occurredAt) {
	}

	public record PageInfoView(String nextCursor, boolean hasNext) {
	}
}
