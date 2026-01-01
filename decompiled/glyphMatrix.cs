

// Assembly-CSharp, Version=0.0.0.0, Culture=neutral, PublicKeyToken=null
// SuperStructureProjector.GlyphMatrix
using RWCustom;
using UnityEngine;

public class GlyphMatrix : Glyph
{
	public IntVector2 size;

	public IntVector2 maxSize;

	public GlyphMatrix(SuperStructureProjector projector, IntVector2 pos)
		: base(projector, pos)
	{
		maxSize = new IntVector2((int)Mathf.Lerp(1f, 70f, Random.value * projector.effect.amount), (int)Mathf.Lerp(1f, 70f, Random.value * projector.effect.amount));
		size = new IntVector2(Random.Range(1, maxSize.x), Random.Range(1, maxSize.y));
		life = Random.Range(10, 600);
	}

	public override void Update(bool eu)
	{
		base.Update(eu);
		if (Random.value < 0.05f)
		{
			if (Random.value < 0.5f)
			{
				size.x = Custom.IntClamp(size.x + ((!(Random.value < 0.5f)) ? 1 : (-1)), 1, maxSize.x);
			}
			else
			{
				size.y = Custom.IntClamp(size.y + ((!(Random.value < 0.5f)) ? 1 : (-1)), 1, maxSize.y);
			}
		}
	}

	public override void InitiateSprites(RoomCamera.SpriteLeaser sLeaser, RoomCamera rCam)
	{
		sLeaser.sprites = new FSprite[1];
		sLeaser.sprites[0] = new FSprite("glyphs");
		sLeaser.sprites[0].shader = rCam.room.game.rainWorld.Shaders["GlyphProjection"];
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
			Vector2 vector = projector.GridPos(pos, timeStacker) + rCam.room.cameraPositions[rCam.currentCameraPosition];
			if (size.x % 2 == 1)
			{
				vector.x -= 7.5f;
			}
			if (size.y % 2 == 1)
			{
				vector.y -= 7.5f;
			}
			sLeaser.sprites[0].scaleX = (float)size.x * 15f / 750f;
			sLeaser.sprites[0].scaleY = (float)size.y * 15f / 15f;
			sLeaser.sprites[0].x = vector.x - camPos.x;
			sLeaser.sprites[0].y = vector.y - camPos.y;
			sLeaser.sprites[0].color = new Color(0f, 0f, glyphCol);
			sLeaser.sprites[0].alpha = Mathf.InverseLerp(life, 0f, counter) * 0.5f;
			if (base.slatedForDeletetion || room != rCam.room)
			{
				sLeaser.CleanSpritesAndRemove();
			}
		}
	}
}
