package lila.tournament
package crud

import chess.{ Clock, Mode }
import chess.format.Fen

import lila.common.config.MaxPerPage
import lila.common.paginator.Paginator
import lila.db.dsl.*
import lila.db.paginator.Adapter
import lila.user.{ User, Me }
import lila.tournament.BSONHandlers.given

final class CrudApi(tournamentRepo: TournamentRepo, crudForm: CrudForm):

  def list = tournamentRepo uniques 50

  export tournamentRepo.{ uniqueById as one }

  def editForm(tour: Tournament) =
    crudForm(tour.some) fill CrudForm.Data(
      id = tour.id,
      name = tour.name,
      homepageHours = ~tour.spotlight.flatMap(_.homepageHours),
      clockTime = tour.clock.limitInMinutes,
      clockIncrement = tour.clock.incrementSeconds,
      minutes = tour.minutes,
      variant = tour.variant.id,
      position = tour.position.map(_ into Fen.Epd),
      date = tour.startsAt.dateTime,
      image = ~tour.spotlight.flatMap(_.iconImg),
      headline = tour.spotlight.so(_.headline),
      description = tour.spotlight.so(_.description),
      conditions = tour.conditions,
      berserkable = !tour.noBerserk,
      rated = tour.isRated,
      streakable = tour.streakable,
      teamBattle = tour.isTeamBattle,
      hasChat = tour.hasChat
    )

  def update(old: Tournament, data: CrudForm.Data) =
    tournamentRepo update updateTour(old, data) void

  def createForm(using Me) = crudForm.newForm(none)

  def create(data: CrudForm.NewData)(using Me): Fu[Tournament] =
    val tour = data.toTour
    tournamentRepo insert tour inject tour

  def clone(old: Tournament) =
    old.copy(
      name = s"${old.name} (clone)",
      startsAt = nowInstant plusDays 7
    )

  def paginator(page: Int)(using Executor) =
    Paginator[Tournament](
      adapter = new Adapter[Tournament](
        collection = tournamentRepo.coll,
        selector = tournamentRepo.selectUnique,
        projection = none,
        sort = $doc("startsAt" -> -1)
      ),
      currentPage = page,
      maxPerPage = MaxPerPage(20)
    )

  private def updateTour(tour: Tournament, data: CrudForm.Data) =
    import data.*
    val clock = Clock.Config(Clock.LimitSeconds((clockTime * 60).toInt), clockIncrement)
    tour.copy(
      name = name,
      clock = clock,
      minutes = minutes,
      variant = realVariant,
      mode = if data.rated then Mode.Rated else Mode.Casual,
      startsAt = date.instant,
      schedule = Schedule(
        freq = Schedule.Freq.Unique,
        speed = Schedule.Speed.fromClock(clock),
        variant = realVariant,
        position = realPosition,
        at = date
      ).some,
      spotlight = Spotlight(
        headline = headline,
        description = description,
        homepageHours = homepageHours.some.filterNot(0 ==),
        iconFont = none,
        iconImg = image.some.filter(_.nonEmpty)
      ).some,
      position = data.realPosition,
      noBerserk = !data.berserkable,
      noStreak = !data.streakable,
      teamBattle = data.teamBattle option (tour.teamBattle | TeamBattle(Set.empty, 10)),
      hasChat = data.hasChat,
      conditions = data.conditions
        .copy(teamMember = tour.conditions.teamMember) // can't change that
    )
