/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.repository;

import alfio.model.WaitingQueueSubscription;
import ch.digitalfondue.npjt.Bind;
import ch.digitalfondue.npjt.Query;
import ch.digitalfondue.npjt.QueryRepository;

import java.time.ZonedDateTime;
import java.util.List;

@QueryRepository
public interface WaitingQueueRepository {

    @Query("select * from waiting_queue where event_id = :eventId and status = 'WAITING' order by creation")
    List<WaitingQueueSubscription> loadAllWaiting(@Bind("eventId") int eventId);

    @Query("select * from waiting_queue where event_id = :eventId and status = 'WAITING' order by creation limit :max for update")
    List<WaitingQueueSubscription> loadWaiting(@Bind("eventId") int eventId, @Bind("max") int maxNumber);

    @Query("select * from waiting_queue where event_id = :eventId and status = 'WAITING' limit 1")
    WaitingQueueSubscription loadFirstWaiting(@Bind("eventId") int eventId);

    @Query("insert into waiting_queue (event_id, full_name, email_address, creation, status, user_language) values(:eventId, :fullName, :emailAddress, :creation, 'WAITING', :userLanguage)")
    int insert(@Bind("eventId") int eventId, @Bind("fullName") String fullName, @Bind("emailAddress") String emailAddress, @Bind("creation") ZonedDateTime creation, @Bind("userLanguage") String userLanguage);

    @Query("update waiting_queue set status = :status where ticket_reservation_id = :reservationId")
    int updateStatusByReservationId(@Bind("reservationId") String reservationId, @Bind("status") String status);

    @Query("update waiting_queue set status = 'EXPIRED' where ticket_reservation_id in (:ticketReservationIds)")
    int bulkUpdateExpiredReservations(@Bind("ticketReservationIds") List<String> ticketReservationIds);

    @Query("select count(*) from waiting_queue where event_id = :eventId and status = 'WAITING'")
    Integer countWaitingPeople(@Bind("eventId") int eventId);
}