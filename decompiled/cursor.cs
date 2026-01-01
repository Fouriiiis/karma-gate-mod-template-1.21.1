

// Assembly-CSharp, Version=0.0.0.0, Culture=neutral, PublicKeyToken=null
// SuperStructureProjector.Cursor
using RWCustom;
using UnityEngine;

public class Cursor : SuperStructureProjectorPart
{
	public IntVector2 gPos;

	public Vector2 pos;

	public Vector2 lastPos;

	public int size;

	public Cursor(SuperStructureProjector projector, IntVector2 gPos)
		: base(projector)
	{
		this.gPos = gPos;
		pos = projector.GridPos(gPos, 1f);
		lastPos = pos;
		size = Random.Range(1, 4);
		projector.cursors.Add(this);
	}

	public override void Update(bool eu)
	{
		base.Update(eu);
		lastPos = pos;
		pos += Vector2.ClampMagnitude(projector.GridPos(gPos, 1f) - pos, 12f);
		if (Random.value < 0.1f)
		{
			gPos.x += Random.Range(-1, 2);
		}
		if (Random.value < 0.1f)
		{
			gPos.y += Random.Range(-1, 2);
		}
		if (gPos.x < 0 || gPos.x >= projector.entireRoomSize.x || gPos.y < 0 || gPos.y >= projector.entireRoomSize.y)
		{
			Destroy();
		}
	}

	public override void InitiateSprites(RoomCamera.SpriteLeaser sLeaser, RoomCamera rCam)
	{
		sLeaser.sprites = new FSprite[5];
		for (int i = 0; i < 4; i++)
		{
			sLeaser.sprites[i] = new FSprite("pixel");
			sLeaser.sprites[i].anchorX = 0f;
			sLeaser.sprites[i].anchorY = 0f;
			sLeaser.sprites[i].color = new Color(0f, 0f, 0f);
			sLeaser.sprites[i].scaleX = 2f;
			sLeaser.sprites[i].scaleY = 2f;
		}
		sLeaser.sprites[0].scaleY = 768f;
		sLeaser.sprites[1].scaleY = 768f;
		sLeaser.sprites[2].scaleX = 1366f;
		sLeaser.sprites[3].scaleX = 1366f;
		sLeaser.sprites[4] = new FSprite("glyphs");
		sLeaser.sprites[4].scaleX = 15f * (float)size / 750f;
		sLeaser.sprites[4].scaleY = 15f * (float)size / 15f;
		sLeaser.sprites[4].shader = rCam.room.game.rainWorld.Shaders["GlyphProjection"];
		sLeaser.sprites[4].anchorX = 0f;
		sLeaser.sprites[4].anchorY = 0f;
		AddToContainer(sLeaser, rCam, null);
	}

	public override void DrawSprites(RoomCamera.SpriteLeaser sLeaser, RoomCamera rCam, float timeStacker, Vector2 camPos)
	{
		for (int i = 0; i < sLeaser.sprites.Length; i++)
		{
			sLeaser.sprites[i].isVisible = projector.visible;
		}
		if (projector.visible)
		{
			Vector2 vector = Vector2.Lerp(lastPos, pos, timeStacker);
			sLeaser.sprites[4].x = vector.x - camPos.x;
			sLeaser.sprites[4].y = vector.y - camPos.y;
			sLeaser.sprites[0].x = vector.x - camPos.x;
			sLeaser.sprites[1].x = vector.x - camPos.x + 15f * (float)size;
			sLeaser.sprites[2].y = vector.y - camPos.y;
			sLeaser.sprites[3].y = vector.y - camPos.y + 15f * (float)size;
			if (base.slatedForDeletetion || room != rCam.room)
			{
				sLeaser.CleanSpritesAndRemove();
			}
		}
	}
}
